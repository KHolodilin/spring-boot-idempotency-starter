package com.kholodilin.idempotency.demo;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo smoke tests: the application consumes the starter exactly like an external
 * application would. Redis is intentionally unavailable to also verify the fail-open
 * distributed cache behaviour.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoSmokeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // dead Redis: the fail-open distributed cache must not affect correctness
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.timeout", () -> "300ms");
        registry.add("spring.data.redis.connect-timeout", () -> "300ms");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    DataSource dataSource;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    RestClient http;

    @BeforeEach
    void initClient() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> {
                    // keep 4xx/5xx responses for assertions instead of throwing
                })
                .build();
    }

    private ResponseEntity<String> post(String path, String idempotencyKey, String body) {
        return http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private long paymentsCount() {
        return JdbcClient.create(dataSource).sql("SELECT count(*) FROM payments").query(Long.class).single();
    }

    @Test
    void duplicatePostReturnsPreviousResultAndCreatesOnePayment() {
        String body = """
                {"orderId": "order-dup", "recipient": "alice", "amount": 100.00}""";
        long before = paymentsCount();

        ResponseEntity<String> first = post("/api/payments", "smoke-dup-1", body);
        ResponseEntity<String> second = post("/api/payments", "smoke-dup-1", body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode firstJson = JSON.readTree(first.getBody());
        JsonNode secondJson = JSON.readTree(second.getBody());
        assertThat(secondJson.get("paymentId").asString())
                .as("duplicate must replay the same payment")
                .isEqualTo(firstJson.get("paymentId").asString());
        assertThat(paymentsCount()).as("only one payment row must exist").isEqualTo(before + 1);
    }

    @Test
    void sameKeyWithDifferentPayloadReturnsConflict() {
        ResponseEntity<String> first = post("/api/payments", "smoke-conf-1", """
                {"orderId": "order-c1", "recipient": "alice", "amount": 100.00}""");
        ResponseEntity<String> conflicting = post("/api/payments", "smoke-conf-1", """
                {"orderId": "order-c1", "recipient": "alice", "amount": 200.00}""");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(JSON.readTree(conflicting.getBody()).get("code").asString())
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void businessRejectionIsReturnedAndReplayed() {
        String body = """
                {"orderId": "order-rej", "recipient": "alice", "amount": 5000.00}""";
        long before = paymentsCount();

        ResponseEntity<String> first = post("/api/payments", "smoke-rej-1", body);
        ResponseEntity<String> replayed = post("/api/payments", "smoke-rej-1", body);

        for (ResponseEntity<String> response : java.util.List.of(first, replayed)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            JsonNode json = JSON.readTree(response.getBody());
            assertThat(json.get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(json.get("details").get("availableBalance").decimalValue())
                    .isEqualByComparingTo("1000.00");
        }
        assertThat(paymentsCount()).as("rejected operation must not create payments").isEqualTo(before);
    }

    @Test
    void technicalFailureRollsBackAndRetrySucceeds() {
        String body = """
                {"orderId": "order-tech", "recipient": "FAIL_ONCE", "amount": 100.00}""";

        ResponseEntity<String> failed = post("/api/payments", "smoke-tech-1", body);
        assertThat(failed.getStatusCode().is5xxServerError())
                .as("transient technical failure must surface as 5xx")
                .isTrue();

        ResponseEntity<String> retried = post("/api/payments", "smoke-tech-1", body);
        assertThat(retried.getStatusCode())
                .as("rollback left no committed record, retry executes from scratch")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void refundEndpointDemonstratesFoldStyle() {
        ResponseEntity<String> rejected = post("/api/refunds", "smoke-ref-1", """
                {"paymentId": "pay-1", "amount": 600.00}""");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(JSON.readTree(rejected.getBody()).get("code").asString())
                .isEqualTo("REFUND_LIMIT_EXCEEDED");

        ResponseEntity<String> ok = post("/api/refunds", "smoke-ref-2", """
                {"paymentId": "pay-1", "amount": 100.00}""");
        ResponseEntity<String> duplicate = post("/api/refunds", "smoke-ref-2", """
                {"paymentId": "pay-1", "amount": 100.00}""");

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(duplicate.getBody()).get("refundId").asString())
                .isEqualTo(JSON.readTree(ok.getBody()).get("refundId").asString());
    }
}
