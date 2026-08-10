package com.kholodilin.idempotency.core;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.idempotency.spi.TransactionContext;
import com.kholodilin.idempotency.testsupport.InMemoryCache;
import com.kholodilin.idempotency.testsupport.InMemoryPersistenceCleanup;
import com.kholodilin.idempotency.testsupport.InMemoryStore;
import org.junit.jupiter.api.Test;

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
        TransactionContext inactive = new TransactionContext() {
            @Override
            public boolean isActive() {
                return false;
            }

            @Override
            public void afterCommit(Runnable action) {
                action.run();
            }
        };
        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(store)
                .clock(clock)
                .transactionContext(inactive)
                .build();

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
    void persistenceHitAfterAcquireConflictIsPromotedToBothCaches() {
        DefaultIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();
        IdempotencyRecord record = completedRecord();
        store.data.put(record.key(), record);

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(store.acquireCalls).hasValue(1);
        assertThat(store.findCalls).hasValue(1);
        assertThat(local.data).containsEntry(record.key(), record);
        assertThat(distributed.data).containsEntry(record.key(), record);
    }

    @Test
    void insertFirstSkipsPersistenceFindOnCacheMiss() {
        DefaultIdempotencyService service = serviceBuilder().build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(store.acquireCalls).hasValue(1);
        assertThat(store.findCalls)
                .as("default insert-first must not find before acquire")
                .hasValue(0);
    }

    @Test
    void lookupBeforeAcquireReplaysTerminalWithoutAcquire() {
        IdempotencyRecord record = completedRecord();
        store.data.put(record.key(), record);
        DefaultIdempotencyService service =
                serviceBuilder().lookupBeforeAcquire(true).build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(store.findCalls).hasValue(1);
        assertThat(store.acquireCalls).hasValue(0);
    }

    @Test
    void worksWithCachesDisabled() {
        DefaultIdempotencyService service = serviceBuilder().build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);
        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
    }

    @Test
    void expiredTerminalCacheEntryIsStillReplayed() {
        DefaultIdempotencyService service = serviceBuilder().localCache(local).build();
        IdempotencyRecord base = completedRecord();
        IdempotencyRecord expired = new IdempotencyRecord(
                base.key(),
                IdempotencyStatus.COMPLETED,
                base.requestHash(),
                PaymentResult.class.getName(),
                "{\"paymentId\":\"pay-42\"}",
                null,
                NOW.minusSeconds(7200),
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600));
        local.data.put(expired.key(), expired);

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(result.isSuccess()).isTrue();
        assertThat(local.evicts).hasValue(0);
        assertThat(store.acquireCalls).hasValue(0);
    }

    @Test
    void physicalCleanupAllowsNewAcquire() {
        DefaultIdempotencyService service =
                serviceBuilder().persistenceTtl(Duration.ofHours(1)).build();
        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);
        assertThat(actionCalls).hasValue(1);

        new InMemoryPersistenceCleanup(store).deleteExpired(NOW.plus(Duration.ofHours(2)), 100);

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);
        assertThat(actionCalls).hasValue(2);
    }

    @Test
    void acquireConflictAndWaitAreRecorded() {
        IdempotencyMetrics metrics = mock(IdempotencyMetrics.class);
        IdempotencyRecord record = completedRecord();
        store.data.put(record.key(), record);
        DefaultIdempotencyService service = serviceBuilder().metrics(metrics).build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        verify(metrics).acquireConflict();
        verify(metrics).acquireWait(any(Duration.class));
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
        List<Runnable> deferred = new ArrayList<>();
        TransactionContext capturing = new TransactionContext() {
            @Override
            public boolean isActive() {
                return true;
            }

            @Override
            public void afterCommit(Runnable action) {
                deferred.add(action);
            }
        };
        DefaultIdempotencyService service = serviceBuilder()
                .localCache(local)
                .distributedCache(distributed)
                .transactionContext(capturing)
                .build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(local.data).as("cache must not contain uncommitted state").isEmpty();
        assertThat(distributed.data).isEmpty();
        assertThat(deferred).hasSize(1);

        deferred.forEach(Runnable::run);

        IdempotencyKey key = new IdempotencyKey(OPERATION, KEY);
        assertThat(local.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(distributed.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    // ---------------------------------------------------------------- concurrency edges

    @Test
    void lostAcquireRaceReplaysRecordCommittedByConcurrentRequest() {
        PersistenceStore racingStore = mock(PersistenceStore.class);
        IdempotencyRecord committedByOther = completedRecord();
        when(racingStore.find(any())).thenReturn(Optional.of(committedByOther));
        when(racingStore.acquire(any(), any(), any(), any())).thenReturn(false);

        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(racingStore)
                .clock(clock)
                .requireActiveTransaction(false)
                .build();

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(((Success<PaymentResult>) result).value()).isEqualTo(new PaymentResult("pay-42"));
        verify(racingStore, times(1)).find(any());
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

    @Test
    void acquireLoopExhaustionThrowsIllegalStateException() {
        PersistenceStore racingStore = mock(PersistenceStore.class);
        when(racingStore.find(any())).thenReturn(Optional.empty());
        when(racingStore.acquire(any(), any(), any(), any())).thenReturn(false);

        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(racingStore)
                .clock(clock)
                .requireActiveTransaction(false)
                .build();

        assertThatThrownBy(() -> service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after 3 attempts");
        assertThat(actionCalls).hasValue(0);
        verify(racingStore, times(3)).acquire(any(), any(), any(), any());
    }

    @Test
    void nonTerminalCacheEntryIsEvictedAndAcquisitionContinues() {
        DefaultIdempotencyService service = serviceBuilder().localCache(local).build();
        IdempotencyRecord processing = IdempotencyRecord.processing(
                new IdempotencyKey(OPERATION, KEY), completedRecord().requestHash(), NOW.minusSeconds(1), null);
        local.data.put(processing.key(), processing);

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
        assertThat(result.isSuccess()).isTrue();
        assertThat(local.evicts).hasValue(1);
    }

    @Test
    void lookupBeforeAcquireSkipsNonTerminalPersistenceRow() {
        IdempotencyRecord processing = IdempotencyRecord.processing(
                new IdempotencyKey(OPERATION, KEY), completedRecord().requestHash(), NOW.minusSeconds(1), null);
        store.data.put(processing.key(), processing);
        DefaultIdempotencyService service =
                serviceBuilder().lookupBeforeAcquire(true).build();

        // acquire fails (row present), find returns non-terminal → loop exhausts
        assertThatThrownBy(() -> service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after 3 attempts");
        assertThat(actionCalls).hasValue(0);
        assertThat(store.findCalls.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void builderAcceptsCustomFingerprintAndSerializer() {
        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(store)
                .fingerprintStrategy(request -> "fixed-hash")
                .serializer(new com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer())
                .clock(clock)
                .requireActiveTransaction(false)
                .build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(store.data.get(new IdempotencyKey(OPERATION, KEY)).requestHash())
                .isEqualTo("fixed-hash");
    }

    @Test
    void immediateTransactionContextReportsActiveAndRunsAfterCommit() {
        var ran = new java.util.concurrent.atomic.AtomicBoolean();
        assertThat(com.kholodilin.idempotency.spi.TransactionContext.IMMEDIATE.isActive())
                .isTrue();
        com.kholodilin.idempotency.spi.TransactionContext.IMMEDIATE.afterCommit(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void activeImmediateTransactionSatisfiesRequireActiveTransaction() {
        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(store)
                .clock(clock)
                .transactionContext(TransactionContext.IMMEDIATE)
                .requireActiveTransaction(true)
                .build();

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(result.isSuccess()).isTrue();
        assertThat(actionCalls).hasValue(1);
    }

    @Test
    void rejectedWithoutDetailsPersistsNullPayload() {
        DefaultIdempotencyService service = serviceBuilder().build();

        ExecutionResult<PaymentResult> first =
                service.execute(OPERATION, KEY, command, PaymentResult.class, () -> ExecutionResult.rejected("GONE"));

        assertThat(first).isInstanceOf(Rejected.class);
        assertThat(store.data.get(new IdempotencyKey(OPERATION, KEY)).resultPayload())
                .isNull();
    }

    @Test
    void distributedOnlyCacheHitReplaysWithoutLocalPromotion() {
        DefaultIdempotencyService service =
                serviceBuilder().distributedCache(distributed).build();
        IdempotencyRecord record = completedRecord();
        distributed.data.put(record.key(), record);

        ExecutionResult<PaymentResult> result =
                service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(0);
        assertThat(result.isSuccess()).isTrue();
        assertThat(store.acquireCalls).hasValue(0);
        assertThat(local.data).isEmpty();
    }

    @Test
    void lookupBeforeAcquireWithEmptyStoreProceedsToAcquire() {
        DefaultIdempotencyService service =
                serviceBuilder().lookupBeforeAcquire(true).build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(actionCalls).hasValue(1);
        assertThat(store.findCalls).hasValue(1);
        assertThat(store.acquireCalls).hasValue(1);
    }

    @Test
    void onlyDistributedCacheIsPopulatedAfterCommit() {
        DefaultIdempotencyService service =
                serviceBuilder().distributedCache(distributed).build();

        service.execute(OPERATION, KEY, command, PaymentResult.class, this::countingAction);

        assertThat(distributed.data).containsKey(new IdempotencyKey(OPERATION, KEY));
        assertThat(local.data).isEmpty();
    }

    @Test
    void replayThrowsOnUnexpectedProcessingRecord() throws Exception {
        DefaultIdempotencyService service = serviceBuilder().build();
        IdempotencyRecord processing =
                IdempotencyRecord.processing(new IdempotencyKey(OPERATION, KEY), "hash", NOW, null);
        var replay = DefaultIdempotencyService.class.getDeclaredMethod(
                "replay", IdempotencyRecord.class, String.class, Class.class);
        replay.setAccessible(true);

        assertThatThrownBy(() -> {
                    try {
                        replay.invoke(service, processing, "hash", PaymentResult.class);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                })
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected committed PROCESSING");
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
}
