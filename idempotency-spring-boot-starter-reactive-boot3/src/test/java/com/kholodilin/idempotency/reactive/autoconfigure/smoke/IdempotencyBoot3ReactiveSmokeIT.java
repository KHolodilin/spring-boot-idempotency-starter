package com.kholodilin.idempotency.reactive.autoconfigure.smoke;

import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.reactive.ReactiveIdempotencyService;
import com.kholodilin.idempotency.reactive.autoconfigure.IdempotencyAutoConfiguration;
import com.kholodilin.idempotency.reactive.autoconfigure.IdempotencyCaffeineAutoConfiguration;
import com.kholodilin.idempotency.reactive.autoconfigure.IdempotencyJacksonAutoConfiguration;
import com.kholodilin.idempotency.reactive.autoconfigure.IdempotencyR2dbcAutoConfiguration;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Boot 3 reactive starter smoke: R2DBC autoconfig + Postgres execute/replay
 * (success and reject) through {@link ReactiveIdempotencyService}.
 */
@Testcontainers(disabledWithoutDocker = true)
class IdempotencyBoot3ReactiveSmokeIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private ApplicationContextRunner runner() {
        String r2dbcUrl = "r2dbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(5432)
                + "/"
                + POSTGRES.getDatabaseName();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        R2dbcAutoConfiguration.class,
                        R2dbcTransactionManagerAutoConfiguration.class,
                        IdempotencyJacksonAutoConfiguration.class,
                        IdempotencyR2dbcAutoConfiguration.class,
                        IdempotencyCaffeineAutoConfiguration.class,
                        IdempotencyAutoConfiguration.class))
                .withPropertyValues(
                        "spring.r2dbc.url=" + r2dbcUrl,
                        "spring.r2dbc.username=" + POSTGRES.getUsername(),
                        "spring.r2dbc.password=" + POSTGRES.getPassword(),
                        "idempotency.persistence.schema.mode=create",
                        "idempotency.persistence.table-name=idempotency_records");
    }

    record Command(String orderId, int amount) {}

    record PaymentResult(String paymentId) {}

    record RejectDetails(long amount, long balance) {}

    @Test
    void successIsPersistedAndReplayedThroughAutoconfig() {
        runner().run(context -> {
            ReactiveIdempotencyService service = context.getBean(ReactiveIdempotencyService.class);
            TransactionalOperator tx = TransactionalOperator.create(context.getBean(ReactiveTransactionManager.class));
            AtomicInteger actionCalls = new AtomicInteger();
            Command command = new Command("o-smoke", 10);

            ExecutionResult<PaymentResult> first = tx.transactional(service.operation("CREATE_PAYMENT")
                            .key("smoke-ok-1")
                            .request(command)
                            .execute(PaymentResult.class, () -> {
                                actionCalls.incrementAndGet();
                                return Mono.just(ExecutionResult.success(new PaymentResult("pay-42")));
                            }))
                    .block();

            ExecutionResult<PaymentResult> replayed = tx.transactional(service.operation("CREATE_PAYMENT")
                            .key("smoke-ok-1")
                            .request(command)
                            .execute(
                                    PaymentResult.class,
                                    () -> Mono.error(new AssertionError("action must not run on replay"))))
                    .block();

            assertThat(actionCalls).hasValue(1);
            assertThat(replayed).isEqualTo(first);
            assertThat(((Success<PaymentResult>) replayed).value()).isEqualTo(new PaymentResult("pay-42"));
        });
    }

    @Test
    void rejectionIsPersistedAndReplayedWithSameDetailsJson() {
        runner().run(context -> {
            ReactiveIdempotencyService service = context.getBean(ReactiveIdempotencyService.class);
            IdempotencySerializer serializer = context.getBean(IdempotencySerializer.class);
            TransactionalOperator tx = TransactionalOperator.create(context.getBean(ReactiveTransactionManager.class));
            Command command = new Command("o-rej", 1500);
            RejectDetails details = new RejectDetails(1500, 200);

            ExecutionResult<PaymentResult> first = tx.transactional(service.operation("CREATE_PAYMENT")
                            .key("smoke-rej-1")
                            .request(command)
                            .execute(
                                    PaymentResult.class,
                                    () -> Mono.just(
                                            ExecutionResult.rejected("INSUFFICIENT_FUNDS", details, serializer))))
                    .block();

            ExecutionResult<PaymentResult> replayed = tx.transactional(service.operation("CREATE_PAYMENT")
                            .key("smoke-rej-1")
                            .request(command)
                            .execute(
                                    PaymentResult.class,
                                    () -> Mono.error(new AssertionError("action must not run on replay"))))
                    .block();

            Rejected<PaymentResult> firstRejected = (Rejected<PaymentResult>) first;
            Rejected<PaymentResult> rejected = (Rejected<PaymentResult>) replayed;
            assertThat(rejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(rejected.detailsJson()).isEqualTo(firstRejected.detailsJson());
            assertThat(rejected.detailsAs(RejectDetails.class, serializer)).isEqualTo(details);
        });
    }
}
