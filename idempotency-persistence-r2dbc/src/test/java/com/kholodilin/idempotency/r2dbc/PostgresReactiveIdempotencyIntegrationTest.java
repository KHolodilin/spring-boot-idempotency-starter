package com.kholodilin.idempotency.r2dbc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.reactive.core.DefaultReactiveIdempotencyService;
import com.kholodilin.idempotency.reactive.core.DefaultReactiveIdempotencyServiceBuilder;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.PROTOCOL;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresReactiveIdempotencyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static ConnectionFactory connectionFactory;
    static DatabaseClient databaseClient;
    static TransactionalOperator tx;
    static ExecutorService executor;

    final MutableClock clock = new MutableClock(Instant.parse("2026-08-09T00:00:00Z"));
    R2dbcPersistenceStore store;
    DefaultReactiveIdempotencyService service;
    final AtomicInteger actionCalls = new AtomicInteger();

    record Command(String orderId, BigDecimal amount) {}

    record PaymentResult(String paymentId) {}

    final Command command = new Command("o-1", new BigDecimal("10.00"));

    @BeforeAll
    static void initInfrastructure() {
        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(DRIVER, "pool")
                .option(PROTOCOL, "postgresql")
                .option(HOST, POSTGRES.getHost())
                .option(PORT, POSTGRES.getMappedPort(5432))
                .option(USER, POSTGRES.getUsername())
                .option(PASSWORD, POSTGRES.getPassword())
                .option(DATABASE, POSTGRES.getDatabaseName())
                .build();
        connectionFactory = ConnectionFactories.get(options);
        databaseClient = DatabaseClient.create(connectionFactory);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        executor = Executors.newFixedThreadPool(4);

        new R2dbcSchemaManager(databaseClient, "idempotency_records", SchemaMode.CREATE)
                .initialize()
                .block();
    }

    @AfterAll
    static void shutdown() {
        executor.shutdownNow();
    }

    @BeforeEach
    void initSubjects() {
        databaseClient
                .sql("DELETE FROM idempotency_records")
                .fetch()
                .rowsUpdated()
                .block();
        store = new R2dbcPersistenceStore(databaseClient, "idempotency_records");
        service = new DefaultReactiveIdempotencyServiceBuilder(store)
                .clock(clock)
                .persistenceTtl(Duration.ofHours(24))
                .build();
        actionCalls.set(0);
    }

    @Test
    void schemaCreateIsIdempotentAndValidatePasses() {
        StepVerifier.create(new R2dbcSchemaManager(databaseClient, "idempotency_records", SchemaMode.CREATE)
                        .initialize()
                        .then(new R2dbcSchemaManager(databaseClient, "idempotency_records", SchemaMode.VALIDATE)
                                .initialize()))
                .verifyComplete();
    }

    @Test
    void schemaValidateFailsForMissingTable() {
        StepVerifier.create(new R2dbcSchemaManager(databaseClient, "no_such_table", SchemaMode.VALIDATE).initialize())
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("no_such_table"));
    }

    @Test
    void schemaNoneDoesNothing() {
        StepVerifier.create(
                        new R2dbcSchemaManager(databaseClient, "another_missing_table", SchemaMode.NONE).initialize())
                .verifyComplete();
    }

    @Test
    void acquireWinsOnceAndMapsAllFields() {
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "map-1");
        Instant createdAt = Instant.parse("2026-08-09T00:00:00Z");
        Instant completedAt = Instant.parse("2026-08-09T00:00:01Z");
        Instant expiresAt = Instant.parse("2026-08-10T00:00:00Z");

        tx.transactional(store.acquire(key, "hash-1", createdAt, expiresAt)
                        .doOnNext(first -> assertThat(first).isTrue())
                        .then(store.acquire(key, "hash-1", createdAt, expiresAt))
                        .doOnNext(second -> assertThat(second).isFalse())
                        .then(store.complete(
                                key, PaymentResult.class.getName(), "{\"paymentId\": \"pay-42\"}", completedAt)))
                .block();

        IdempotencyRecord record = store.find(key).block().orElseThrow();
        assertThat(record.key()).isEqualTo(key);
        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.requestHash()).isEqualTo("hash-1");
        assertThat(record.resultType()).isEqualTo(PaymentResult.class.getName());
        assertThat(record.resultPayload()).contains("pay-42");
        assertThat(record.errorCode()).isNull();
        assertThat(record.createdAt()).isEqualTo(createdAt);
        assertThat(record.completedAt()).isEqualTo(completedAt);
        assertThat(record.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void completeRequiresProcessingRow() {
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "guards-1");
        tx.transactional(store.acquire(key, "h", clock.instant(), null)
                        .then(store.complete(key, null, null, clock.instant())))
                .block();

        StepVerifier.create(tx.transactional(store.complete(key, null, null, clock.instant())))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("complete"));
    }

    @Test
    void completedOutcomeIsPersistedAndReplayed() {
        ExecutionResult<PaymentResult> first = tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("abc-123")
                        .request(command)
                        .execute(PaymentResult.class, () -> {
                            actionCalls.incrementAndGet();
                            return Mono.just(ExecutionResult.success(new PaymentResult("pay-42")));
                        }))
                .block();

        ExecutionResult<PaymentResult> replayed = tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("abc-123")
                        .request(command)
                        .execute(
                                PaymentResult.class,
                                () -> Mono.error(new AssertionError("action must not run on replay"))))
                .block();

        assertThat(actionCalls).hasValue(1);
        assertThat(replayed).isEqualTo(first);
        assertThat(((Success<PaymentResult>) replayed).value()).isEqualTo(new PaymentResult("pay-42"));
    }

    @Test
    void rejectedOutcomeIsPersistedAndReplayedIdentically() {
        record Details(long amount, long balance) {}

        ExecutionResult<PaymentResult> first = tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("rej-1")
                        .request(command)
                        .execute(
                                PaymentResult.class,
                                () -> Mono.just(
                                        ExecutionResult.rejected("INSUFFICIENT_FUNDS", new Details(1500, 200)))))
                .block();

        ExecutionResult<PaymentResult> replayed = tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("rej-1")
                        .request(command)
                        .execute(PaymentResult.class, () -> Mono.error(new AssertionError("replay"))))
                .block();

        assertThat(replayed).isEqualTo(first);
        Rejected<PaymentResult> rejected = (Rejected<PaymentResult>) replayed;
        assertThat(rejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejected.details().get("amount").asLong()).isEqualTo(1500);
    }

    @Test
    void technicalFailureRollsBackAndLeavesNoCommittedRecord() {
        StepVerifier.create(tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("fail-1")
                        .request(command)
                        .execute(PaymentResult.class, () -> {
                            actionCalls.incrementAndGet();
                            return Mono.error(new IllegalStateException("connection reset"));
                        })))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("connection reset"));

        assertThat(store.find(new IdempotencyKey("CREATE_PAYMENT", "fail-1")).block())
                .isEmpty();

        ExecutionResult<PaymentResult> retried = tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("fail-1")
                        .request(command)
                        .execute(PaymentResult.class, () -> {
                            actionCalls.incrementAndGet();
                            return Mono.just(ExecutionResult.success(new PaymentResult("pay-2nd")));
                        }))
                .block();

        assertThat(actionCalls).hasValue(2);
        assertThat(((Success<PaymentResult>) retried).value()).isEqualTo(new PaymentResult("pay-2nd"));
    }

    @Test
    void sameKeyWithDifferentPayloadConflicts() {
        tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("conf-1")
                        .request(command)
                        .execute(
                                PaymentResult.class,
                                () -> Mono.just(ExecutionResult.success(new PaymentResult("pay-42")))))
                .block();

        Command different = new Command("o-1", new BigDecimal("999.99"));

        StepVerifier.create(tx.transactional(service.operation("CREATE_PAYMENT")
                        .key("conf-1")
                        .request(different)
                        .execute(
                                PaymentResult.class,
                                () -> Mono.just(ExecutionResult.success(new PaymentResult("pay-43"))))))
                .verifyError(IdempotencyConflictException.class);
    }

    @Test
    void expiredRecordRemainsVisibleUntilPhysicallyDeleted() {
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "exp-1");
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plusSeconds(60);
        R2dbcIdempotencyPersistenceCleanup cleanup =
                new R2dbcIdempotencyPersistenceCleanup(databaseClient, "idempotency_records");

        tx.transactional(store.acquire(key, "hash-old", createdAt, expiresAt)
                        .then(store.complete(key, null, null, createdAt)))
                .block();

        clock.advance(Duration.ofSeconds(120));

        assertThat(store.find(key).block()).isPresent();
        assertThat(tx.transactional(store.acquire(
                                key,
                                "hash-new",
                                clock.instant(),
                                clock.instant().plusSeconds(60)))
                        .block())
                .isFalse();

        assertThat(tx.transactional(cleanup.deleteExpired(clock.instant(), 100)).block())
                .isEqualTo(1);
        assertThat(store.find(key).block()).isEmpty();
    }

    @Test
    void concurrentDuplicatesExecuteActionExactlyOnce() throws Exception {
        Command payload = new Command("o-9", new BigDecimal("50.00"));

        CompletableFuture<ExecutionResult<PaymentResult>> first = CompletableFuture.supplyAsync(
                () -> tx.transactional(service.operation("CREATE_PAYMENT")
                                .key("race-1")
                                .request(payload)
                                .execute(PaymentResult.class, () -> {
                                    actionCalls.incrementAndGet();
                                    return Mono.delay(Duration.ofMillis(700))
                                            .thenReturn(ExecutionResult.success(new PaymentResult("pay-winner")));
                                }))
                        .block(),
                executor);

        Thread.sleep(200);

        CompletableFuture<ExecutionResult<PaymentResult>> second = CompletableFuture.supplyAsync(
                () -> tx.transactional(service.operation("CREATE_PAYMENT")
                                .key("race-1")
                                .request(payload)
                                .execute(PaymentResult.class, () -> {
                                    actionCalls.incrementAndGet();
                                    return Mono.just(ExecutionResult.success(new PaymentResult("pay-loser")));
                                }))
                        .block(),
                executor);

        List<ExecutionResult<PaymentResult>> results =
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

        assertThat(actionCalls).hasValue(1);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(((Success<PaymentResult>) results.get(1)).value()).isEqualTo(new PaymentResult("pay-winner"));
    }

    @Test
    void cleanupRejectsNonPositiveLimit() {
        R2dbcIdempotencyPersistenceCleanup cleanup = new R2dbcIdempotencyPersistenceCleanup(databaseClient);
        StepVerifier.create(cleanup.deleteExpired(clock.instant(), 0)).verifyError(IllegalArgumentException.class);
    }

    static final class MutableClock extends Clock {

        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
