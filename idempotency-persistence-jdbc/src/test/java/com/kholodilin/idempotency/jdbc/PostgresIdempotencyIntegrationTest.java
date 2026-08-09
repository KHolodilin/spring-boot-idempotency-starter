package com.kholodilin.idempotency.jdbc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.core.DefaultIdempotencyService;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresIdempotencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;
    static TransactionTemplate tx;
    static ExecutorService executor;

    final MutableClock clock = new MutableClock(Instant.parse("2026-08-09T00:00:00Z"));
    JdbcPersistenceStore store;
    DefaultIdempotencyService service;
    final AtomicInteger actionCalls = new AtomicInteger();

    record Command(String orderId, BigDecimal amount) {}

    record PaymentResult(String paymentId) {}

    final Command command = new Command("o-1", new BigDecimal("10.00"));

    @BeforeAll
    static void initInfrastructure() {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(POSTGRES.getJdbcUrl());
        pg.setUser(POSTGRES.getUsername());
        pg.setPassword(POSTGRES.getPassword());
        dataSource = pg;
        tx = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        executor = Executors.newFixedThreadPool(4);

        new JdbcSchemaManager(dataSource, "idempotency_records", SchemaMode.CREATE).initialize();
    }

    @AfterAll
    static void shutdown() {
        executor.shutdownNow();
    }

    @BeforeEach
    void initSubjects() {
        JdbcClient.create(dataSource).sql("DELETE FROM idempotency_records").update();
        store = new JdbcPersistenceStore(dataSource, "idempotency_records", clock);
        service = DefaultIdempotencyService.builder(store)
                .clock(clock)
                .persistenceTtl(Duration.ofHours(24))
                .build();
        actionCalls.set(0);
    }

    // ------------------------------------------------------------------ schema

    @Test
    void schemaCreateIsIdempotentAndValidatePasses() {
        new JdbcSchemaManager(dataSource, "idempotency_records", SchemaMode.CREATE).initialize();
        new JdbcSchemaManager(dataSource, "idempotency_records", SchemaMode.VALIDATE).initialize();
    }

    @Test
    void schemaValidateFailsForMissingTable() {
        JdbcSchemaManager manager = new JdbcSchemaManager(dataSource, "no_such_table", SchemaMode.VALIDATE);

        assertThatThrownBy(manager::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no_such_table");
    }

    @Test
    void schemaNoneDoesNothing() {
        new JdbcSchemaManager(dataSource, "another_missing_table", SchemaMode.NONE).initialize();
    }

    // ------------------------------------------------------------------ store primitives

    @Test
    void acquireWinsOnceAndMapsAllFields() {
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "map-1");
        Instant createdAt = Instant.parse("2026-08-09T00:00:00Z");
        Instant completedAt = Instant.parse("2026-08-09T00:00:01Z");
        Instant expiresAt = Instant.parse("2026-08-10T00:00:00Z");

        tx.executeWithoutResult(status -> {
            assertThat(store.acquire(key, "hash-1", createdAt, expiresAt)).isTrue();
            assertThat(store.acquire(key, "hash-1", createdAt, expiresAt)).isFalse();
            store.complete(key, PaymentResult.class.getName(), "{\"paymentId\": \"pay-42\"}", completedAt);
        });

        Optional<IdempotencyRecord> found = store.find(key);
        assertThat(found).isPresent();
        IdempotencyRecord record = found.get();
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
    void expiredRecordIsInvisibleAndCanBeTakenOver() {
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "exp-1");
        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plusSeconds(60);

        tx.executeWithoutResult(status -> {
            store.acquire(key, "hash-old", createdAt, expiresAt);
            store.complete(key, null, null, createdAt);
        });

        clock.advance(Duration.ofSeconds(120));

        assertThat(store.find(key))
                .as("expired record must be treated as absent")
                .isEmpty();

        tx.executeWithoutResult(status -> assertThat(store.acquire(
                        key, "hash-new", clock.instant(), clock.instant().plusSeconds(60)))
                .as("expired record must be taken over by a new acquire")
                .isTrue());

        Optional<IdempotencyRecord> takenOver = store.find(key);
        assertThat(takenOver).isPresent();
        assertThat(takenOver.get().requestHash()).isEqualTo("hash-new");
        assertThat(takenOver.get().status()).isEqualTo(IdempotencyStatus.PROCESSING);
    }

    // ------------------------------------------------------------------ service + real transactions

    @Test
    void completedOutcomeIsPersistedAndReplayed() {
        ExecutionResult<PaymentResult> first =
                tx.execute(status -> service.execute("CREATE_PAYMENT", "abc-123", command, PaymentResult.class, () -> {
                    actionCalls.incrementAndGet();
                    return ExecutionResult.success(new PaymentResult("pay-42"));
                }));

        ExecutionResult<PaymentResult> replayed =
                tx.execute(status -> service.execute("CREATE_PAYMENT", "abc-123", command, PaymentResult.class, () -> {
                    throw new AssertionError("action must not run on replay");
                }));

        assertThat(actionCalls).hasValue(1);
        assertThat(replayed).isEqualTo(first);
        assertThat(((Success<PaymentResult>) replayed).value()).isEqualTo(new PaymentResult("pay-42"));
    }

    @Test
    void rejectedOutcomeIsPersistedAndReplayedIdentically() {
        record Details(long amount, long balance) {}

        ExecutionResult<PaymentResult> first = tx.execute(status -> service.execute(
                "CREATE_PAYMENT",
                "rej-1",
                command,
                PaymentResult.class,
                () -> ExecutionResult.rejected("INSUFFICIENT_FUNDS", new Details(1500, 200))));

        ExecutionResult<PaymentResult> replayed =
                tx.execute(status -> service.execute("CREATE_PAYMENT", "rej-1", command, PaymentResult.class, () -> {
                    throw new AssertionError("action must not run on replay");
                }));

        assertThat(replayed).isEqualTo(first);
        Rejected<PaymentResult> rejected = (Rejected<PaymentResult>) replayed;
        assertThat(rejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(rejected.details().get("amount").asLong()).isEqualTo(1500);

        IdempotencyRecord record =
                store.find(new IdempotencyKey("CREATE_PAYMENT", "rej-1")).orElseThrow();
        assertThat(record.status()).isEqualTo(IdempotencyStatus.REJECTED);
        assertThat(record.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void technicalFailureRollsBackAndLeavesNoCommittedRecord() {
        assertThatThrownBy(() -> tx.executeWithoutResult(
                        status -> service.execute("CREATE_PAYMENT", "fail-1", command, PaymentResult.class, () -> {
                            actionCalls.incrementAndGet();
                            throw new IllegalStateException("connection reset");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("connection reset");

        assertThat(store.find(new IdempotencyKey("CREATE_PAYMENT", "fail-1")))
                .as("rollback must leave no committed idempotency record")
                .isEmpty();

        // retry executes the operation from scratch
        ExecutionResult<PaymentResult> retried =
                tx.execute(status -> service.execute("CREATE_PAYMENT", "fail-1", command, PaymentResult.class, () -> {
                    actionCalls.incrementAndGet();
                    return ExecutionResult.success(new PaymentResult("pay-2nd"));
                }));

        assertThat(actionCalls).hasValue(2);
        assertThat(((Success<PaymentResult>) retried).value()).isEqualTo(new PaymentResult("pay-2nd"));
    }

    @Test
    void sameKeyWithDifferentPayloadConflicts() {
        tx.executeWithoutResult(status -> service.execute(
                "CREATE_PAYMENT",
                "conf-1",
                command,
                PaymentResult.class,
                () -> ExecutionResult.success(new PaymentResult("pay-42"))));

        Command different = new Command("o-1", new BigDecimal("999.99"));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.execute(
                        "CREATE_PAYMENT",
                        "conf-1",
                        different,
                        PaymentResult.class,
                        () -> ExecutionResult.success(new PaymentResult("pay-43")))))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void expiredOutcomeAllowsReExecution() {
        tx.executeWithoutResult(
                status -> service.execute("CREATE_PAYMENT", "ttl-1", command, PaymentResult.class, () -> {
                    actionCalls.incrementAndGet();
                    return ExecutionResult.success(new PaymentResult("pay-old"));
                }));

        clock.advance(Duration.ofHours(25)); // beyond the 24h persistence TTL

        ExecutionResult<PaymentResult> second =
                tx.execute(status -> service.execute("CREATE_PAYMENT", "ttl-1", command, PaymentResult.class, () -> {
                    actionCalls.incrementAndGet();
                    return ExecutionResult.success(new PaymentResult("pay-new"));
                }));

        assertThat(actionCalls).hasValue(2);
        assertThat(((Success<PaymentResult>) second).value()).isEqualTo(new PaymentResult("pay-new"));
    }

    @Test
    void concurrentDuplicatesExecuteActionExactlyOnce() throws Exception {
        Command payload = new Command("o-9", new BigDecimal("50.00"));

        CompletableFuture<ExecutionResult<PaymentResult>> first = CompletableFuture.supplyAsync(
                () -> tx.execute(
                        status -> service.execute("CREATE_PAYMENT", "race-1", payload, PaymentResult.class, () -> {
                            actionCalls.incrementAndGet();
                            sleep(700); // hold the transaction so the duplicate blocks on the PK index
                            return ExecutionResult.success(new PaymentResult("pay-winner"));
                        })),
                executor);

        Thread.sleep(200); // let the first request acquire the key

        CompletableFuture<ExecutionResult<PaymentResult>> second = CompletableFuture.supplyAsync(
                () -> tx.execute(
                        status -> service.execute("CREATE_PAYMENT", "race-1", payload, PaymentResult.class, () -> {
                            actionCalls.incrementAndGet();
                            return ExecutionResult.success(new PaymentResult("pay-loser"));
                        })),
                executor);

        List<ExecutionResult<PaymentResult>> results =
                List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

        assertThat(actionCalls).as("business action must execute exactly once").hasValue(1);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(((Success<PaymentResult>) results.get(1)).value()).isEqualTo(new PaymentResult("pay-winner"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
