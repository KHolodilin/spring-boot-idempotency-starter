package com.kholodilin.idempotency.core;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.exception.MissingTransactionException;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultIdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final String OPERATION = "CREATE_PAYMENT";
    private static final String KEY = "abc-123";

    record Command(String orderId, BigDecimal amount) {}

    record PaymentResult(String paymentId) {}

    record Details(long amount, long balance) {}

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryStore store = new InMemoryStore(clock);
    private final InMemoryCache local = new InMemoryCache();
    private final InMemoryCache distributed = new InMemoryCache();
    private final Command command = new Command("o-1", new BigDecimal("10.00"));
    private final AtomicInteger actionCalls = new AtomicInteger();

    @BeforeEach
    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private DefaultIdempotencyServiceBuilder serviceBuilder() {
        return new DefaultIdempotencyServiceBuilder(store).clock(clock).requireActiveTransaction(false);
    }

    private ExecutionResult<PaymentResult> countingAction() {
        actionCalls.incrementAndGet();
        return ExecutionResult.success(new PaymentResult("pay-42"));
    }

    @Test
    void firstExecutionRunsActionAndPersistsCompleted() {
        DefaultIdempotencyService service =
                serviceBuilder().persistenceTtl(Duration.ofHours(24)).build();

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
        assertThat(result).isEqualTo(ExecutionResult.success(new PaymentResult("pay-42")));

        IdempotencyRecord record = store.data.get(new IdempotencyKey(OPERATION, KEY));
        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.resultType()).isEqualTo(PaymentResult.class.getName());
        assertThat(record.resultPayload()).contains("pay-42");
        assertThat(record.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void duplicateRequestReplaysCompletedWithoutExecutingAction() {
        DefaultIdempotencyService service = serviceBuilder().build();

        ExecutionResult<PaymentResult> first =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);
        ExecutionResult<PaymentResult> second =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void rejectedOutcomeIsPersistedAndReplayedIdentically() {
        DefaultIdempotencyService service = serviceBuilder().build();

        ExecutionResult<PaymentResult> first = service.execute(
                OPERATION,
                KEY,
                command,
                PaymentResult.class,
                () -> ExecutionResult.rejected("INSUFFICIENT_FUNDS", new Details(1500, 200)));

        ExecutionResult<PaymentResult> replayed = service.execute(OPERATION, KEY, command, PaymentResult.class, () -> {
            throw new AssertionError("action must not run on replay");
        });

        assertThat(store.data.get(new IdempotencyKey(OPERATION, KEY)).status()).isEqualTo(IdempotencyStatus.REJECTED);
        Rejected<PaymentResult> firstRejected = (Rejected<PaymentResult>) first;
        Rejected<PaymentResult> replayedRejected = (Rejected<PaymentResult>) replayed;
        assertThat(replayedRejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(replayedRejected.details()).isEqualTo(firstRejected.details());
        assertThat(replayedRejected.detailsAs(Details.class)).isEqualTo(new Details(1500, 200));
    }

    @Test
    void sameKeyWithDifferentPayloadThrowsConflict() {
        DefaultIdempotencyService service = serviceBuilder().build();
        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        Command otherPayload = new Command("o-1", new BigDecimal("999.00"));

        assertThatThrownBy(
                        () -> service.execute(OPERATION, KEY, otherPayload, PaymentResult.class, this::countingAction))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(actionCalls).hasValue(1);
    }

    @Test
    void sameKeyForDifferentOperationsAreIndependentRecords() {
        DefaultIdempotencyService service = serviceBuilder().build();

        service.execute("CREATE_ORDER", KEY, command, PaymentResult.class, this::countingAction);
        service.execute("CANCEL_ORDER", KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(2);
        assertThat(store.data)
                .containsKeys(new IdempotencyKey("CREATE_ORDER", KEY), new IdempotencyKey("CANCEL_ORDER", KEY));
    }

    @Test
    void technicalExceptionPropagatesAndNoTerminalStatusIsPersisted() {
        DefaultIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();

        assertThatThrownBy(() -> service.execute(OPERATION, KEY, command, PaymentResult.class, () -> {
                    throw new IllegalStateException("SQL timeout");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SQL timeout");

        IdempotencyRecord record = store.data.get(new IdempotencyKey(OPERATION, KEY));
        assertThat(record.status()).isEqualTo(IdempotencyStatus.PROCESSING);
        assertThat(local.data).isEmpty();
        assertThat(distributed.data).isEmpty();
    }

    @Test
    void voidResultIsPersistedAndReplayed() {
        DefaultIdempotencyService service = serviceBuilder().build();

        ExecutionResult<Void> first =
                service.execute("PROCESS_EVENT", "evt-1", command, Void.class, () -> ExecutionResult.success(null));
        ExecutionResult<Void> replayed = service.execute("PROCESS_EVENT", "evt-1", command, Void.class, () -> {
            throw new AssertionError("action must not run on replay");
        });

        assertThat(first).isInstanceOf(Success.class);
        assertThat(((Success<Void>) replayed).value()).isNull();
        assertThat(store.data.get(new IdempotencyKey("PROCESS_EVENT", "evt-1")).resultPayload())
                .isNull();
    }

    @Test
    void missingTransactionIsRejectedWhenRequired() {
        DefaultIdempotencyService service =
                new DefaultIdempotencyServiceBuilder(store).clock(clock).build();

        assertThatThrownBy(() -> service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction))
                .isInstanceOf(MissingTransactionException.class);
        assertThat(actionCalls).hasValue(0);
    }

    @Test
    void nullActionResultIsRejected() {
        DefaultIdempotencyService service = serviceBuilder().build();

        assertThatThrownBy(() -> service.execute(OPERATION, KEY, command, PaymentResult.class, () -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ExecutionResult.success(null)");
    }

    // ---------------------------------------------------------------- cache routing

    @Test
    void localCacheHitSkipsDistributedCacheAndPersistence() {
        DefaultIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();
        IdempotencyRecord record = completedRecord();
        local.data.put(record.key(), record);

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(result.isSuccess()).isTrue();
        assertThat(distributed.gets).hasValue(0);
        assertThat(store.findCalls).hasValue(0);
        assertThat(store.acquireCalls).hasValue(0);
    }

    @Test
    void distributedCacheHitIsPromotedToLocalCache() {
        DefaultIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();
        IdempotencyRecord record = completedRecord();
        distributed.data.put(record.key(), record);

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(local.data).containsEntry(record.key(), record);
        assertThat(store.findCalls).hasValue(0);
    }

    @Test
    void persistenceHitIsPromotedToBothCaches() {
        DefaultIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();
        IdempotencyRecord record = completedRecord();
        store.data.put(record.key(), record);

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(local.data).containsEntry(record.key(), record);
        assertThat(distributed.data).containsEntry(record.key(), record);
    }

    @Test
    void worksWithCachesDisabled() {
        DefaultIdempotencyService service = serviceBuilder().build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);
        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
    }

    @Test
    void expiredCacheEntryIsEvictedAndOperationExecutesAgain() {
        DefaultIdempotencyService service = serviceBuilder().localCache(local).build();
        IdempotencyRecord expired = completedRecord()
                .completed(PaymentResult.class.getName(), "{\"paymentId\":\"old\"}", NOW.minusSeconds(7200));
        expired = new IdempotencyRecord(
                expired.key(),
                expired.status(),
                expired.requestHash(),
                expired.resultType(),
                expired.resultPayload(),
                null,
                NOW.minusSeconds(7200),
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600));
        local.data.put(expired.key(), expired);

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
        assertThat(local.evicts).hasValue(1);
    }

    @Test
    void conflictIsDetectedOnCachedRecordWithoutTouchingPersistence() {
        DefaultIdempotencyService service = serviceBuilder().localCache(local).build();
        IdempotencyRecord record = completedRecord();
        local.data.put(record.key(), record);

        Command otherPayload = new Command("o-2", new BigDecimal("1.00"));

        assertThatThrownBy(
                        () -> service.execute(OPERATION, KEY, otherPayload, PaymentResult.class, this::countingAction))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(store.findCalls).hasValue(0);
    }

    @Test
    void freshOutcomePopulatesCachesImmediatelyWithoutActiveSynchronization() {
        DefaultIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        IdempotencyKey key = new IdempotencyKey(OPERATION, KEY);
        assertThat(local.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(distributed.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void cachePopulationIsDeferredUntilAfterCommit() {
        DefaultIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

            assertThat(local.data)
                    .as("cache must not contain uncommitted state")
                    .isEmpty();
            assertThat(distributed.data).isEmpty();

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            IdempotencyKey key = new IdempotencyKey(OPERATION, KEY);
            assertThat(local.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(distributed.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // ---------------------------------------------------------------- concurrency edges

    @Test
    void lostAcquireRaceReplaysRecordCommittedByConcurrentRequest() {
        PersistenceStore racingStore = mock(PersistenceStore.class);
        IdempotencyRecord committedByOther = completedRecord();
        when(racingStore.find(any()))
                .thenReturn(Optional.empty()) // initial lookup
                .thenReturn(Optional.of(committedByOther)); // after lost acquire
        when(racingStore.acquire(any(), any(), any(), any())).thenReturn(false);

        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(racingStore)
                .clock(clock)
                .requireActiveTransaction(false)
                .build();

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(((Success<PaymentResult>) result).value()).isEqualTo(new PaymentResult("pay-42"));
    }

    @Test
    void acquireIsRetriedAfterConcurrentRollback() {
        PersistenceStore racingStore = mock(PersistenceStore.class);
        when(racingStore.find(any())).thenReturn(Optional.empty());
        when(racingStore.acquire(any(), any(), any(), any()))
                .thenReturn(false) // lost the race
                .thenReturn(true); // concurrent transaction rolled back, second attempt wins

        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(racingStore)
                .clock(clock)
                .requireActiveTransaction(false)
                .build();

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
        assertThat(result.isSuccess()).isTrue();
        verify(racingStore, times(2)).acquire(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- fixtures

    private IdempotencyRecord completedRecord() {
        DefaultIdempotencyService probe = serviceBuilder().build();
        // fingerprint must match what the service calculates for `command`
        String fingerprint =
                new com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy().calculate(command);
        return IdempotencyRecord.processing(new IdempotencyKey(OPERATION, KEY), fingerprint, NOW.minusSeconds(60), null)
                .completed(PaymentResult.class.getName(), "{\"paymentId\":\"pay-42\"}", NOW.minusSeconds(60));
    }

    static final class InMemoryStore implements PersistenceStore {

        final Map<IdempotencyKey, IdempotencyRecord> data = new HashMap<>();
        final AtomicInteger findCalls = new AtomicInteger();
        final AtomicInteger acquireCalls = new AtomicInteger();
        private final Clock clock;

        InMemoryStore(Clock clock) {
            this.clock = clock;
        }

        @Override
        public Optional<IdempotencyRecord> find(IdempotencyKey key) {
            findCalls.incrementAndGet();
            return Optional.ofNullable(data.get(key)).filter(r -> !r.isExpired(clock.instant()));
        }

        @Override
        public boolean acquire(IdempotencyKey key, String requestHash, Instant createdAt, Instant expiresAt) {
            acquireCalls.incrementAndGet();
            IdempotencyRecord existing = data.get(key);
            if (existing != null && !existing.isExpired(clock.instant())) {
                return false;
            }
            data.put(key, IdempotencyRecord.processing(key, requestHash, createdAt, expiresAt));
            return true;
        }

        @Override
        public void complete(IdempotencyKey key, String resultType, String resultPayload, Instant completedAt) {
            data.compute(key, (k, r) -> r.completed(resultType, resultPayload, completedAt));
        }

        @Override
        public void reject(IdempotencyKey key, String errorCode, String detailsPayload, Instant completedAt) {
            data.compute(key, (k, r) -> r.rejected(errorCode, detailsPayload, completedAt));
        }
    }

    static final class InMemoryCache implements LocalCache, DistributedCache {

        final Map<IdempotencyKey, IdempotencyRecord> data = new HashMap<>();
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger evicts = new AtomicInteger();

        @Override
        public Optional<IdempotencyRecord> get(IdempotencyKey key) {
            gets.incrementAndGet();
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public void put(IdempotencyKey key, IdempotencyRecord record) {
            puts.incrementAndGet();
            data.put(key, record);
        }

        @Override
        public void evict(IdempotencyKey key) {
            evicts.incrementAndGet();
            data.remove(key);
        }
    }
}
