package com.kholodilin.idempotency.reactive.core;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.exception.MissingTransactionException;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.reactive.spi.ReactiveTransactionContext;
import com.kholodilin.idempotency.reactive.testsupport.InMemoryReactiveDistributedCache;
import com.kholodilin.idempotency.reactive.testsupport.InMemoryReactiveStore;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.testsupport.InMemoryCache;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DefaultReactiveIdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
    private static final String OPERATION = "CREATE_PAYMENT";
    private static final String KEY = "abc-123";

    record Command(String orderId, BigDecimal amount) {}

    record PaymentResult(String paymentId) {}

    record Details(long amount, long balance) {}

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryReactiveStore store = new InMemoryReactiveStore();
    private final InMemoryCache local = new InMemoryCache();
    private final InMemoryReactiveDistributedCache distributed = new InMemoryReactiveDistributedCache();
    private final Command command = new Command("o-1", new BigDecimal("10.00"));
    private final AtomicInteger actionCalls = new AtomicInteger();

    private DefaultReactiveIdempotencyServiceBuilder serviceBuilder() {
        return new DefaultReactiveIdempotencyServiceBuilder(store).clock(clock).requireActiveTransaction(false);
    }

    private Mono<ExecutionResult<PaymentResult>> countingAction() {
        actionCalls.incrementAndGet();
        return Mono.just(ExecutionResult.success(new PaymentResult("pay-42")));
    }

    @Test
    void firstExecutionRunsActionAndPersistsCompleted() {
        DefaultReactiveIdempotencyService service =
                serviceBuilder().persistenceTtl(Duration.ofHours(24)).build();

        StepVerifier.create(service.operation(OPERATION)
                        .key(KEY)
                        .request(command)
                        .execute(PaymentResult.class, this::countingAction))
                .assertNext(
                        result -> assertThat(result).isEqualTo(ExecutionResult.success(new PaymentResult("pay-42"))))
                .verifyComplete();

        assertThat(actionCalls).hasValue(1);
        IdempotencyRecord record = store.delegate.data.get(new IdempotencyKey(OPERATION, KEY));
        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.resultType()).isEqualTo(PaymentResult.class.getName());
        assertThat(record.resultPayload()).contains("pay-42");
        assertThat(record.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void duplicateRequestReplaysCompletedWithoutExecutingAction() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();

        ExecutionResult<PaymentResult> first = service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();
        ExecutionResult<PaymentResult> second = service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(actionCalls).hasValue(1);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void rejectedOutcomeIsPersistedAndReplayedIdentically() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();

        ExecutionResult<PaymentResult> first = service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(
                        PaymentResult.class,
                        () -> Mono.just(ExecutionResult.rejected("INSUFFICIENT_FUNDS", new Details(1500, 200))))
                .block();

        ExecutionResult<PaymentResult> replayed = service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, () -> Mono.error(new AssertionError("action must not run on replay")))
                .block();

        assertThat(store.delegate.data.get(new IdempotencyKey(OPERATION, KEY)).status())
                .isEqualTo(IdempotencyStatus.REJECTED);
        Rejected<PaymentResult> firstRejected = (Rejected<PaymentResult>) first;
        Rejected<PaymentResult> replayedRejected = (Rejected<PaymentResult>) replayed;
        assertThat(replayedRejected.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(replayedRejected.details()).isEqualTo(firstRejected.details());
        assertThat(replayedRejected.detailsAs(Details.class)).isEqualTo(new Details(1500, 200));
    }

    @Test
    void sameKeyWithDifferentPayloadThrowsConflict() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();
        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        Command otherPayload = new Command("o-1", new BigDecimal("999.00"));

        StepVerifier.create(service.operation(OPERATION)
                        .key(KEY)
                        .request(otherPayload)
                        .execute(PaymentResult.class, this::countingAction))
                .verifyError(IdempotencyConflictException.class);
        assertThat(actionCalls).hasValue(1);
    }

    @Test
    void sameKeyForDifferentOperationsAreIndependentRecords() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();

        service.operation("CREATE_ORDER")
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();
        service.operation("CANCEL_ORDER")
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(actionCalls).hasValue(2);
        assertThat(store.delegate.data)
                .containsKeys(new IdempotencyKey("CREATE_ORDER", KEY), new IdempotencyKey("CANCEL_ORDER", KEY));
    }

    @Test
    void technicalExceptionPropagatesAndNoTerminalStatusIsPersisted() {
        DefaultReactiveIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();

        StepVerifier.create(service.operation(OPERATION)
                        .key(KEY)
                        .request(command)
                        .execute(PaymentResult.class, () -> Mono.error(new IllegalStateException("SQL timeout"))))
                .verifyErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class).hasMessage("SQL timeout");
                });

        IdempotencyRecord record = store.delegate.data.get(new IdempotencyKey(OPERATION, KEY));
        assertThat(record.status()).isEqualTo(IdempotencyStatus.PROCESSING);
        assertThat(local.data).isEmpty();
        assertThat(distributed.delegate.data).isEmpty();
    }

    @Test
    void voidResultIsPersistedAndReplayed() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();

        ExecutionResult<Void> first = service.operation("PROCESS_EVENT")
                .key("evt-1")
                .request(command)
                .execute(Void.class, () -> Mono.just(ExecutionResult.success(null)))
                .block();
        ExecutionResult<Void> replayed = service.operation("PROCESS_EVENT")
                .key("evt-1")
                .request(command)
                .execute(Void.class, () -> Mono.error(new AssertionError("action must not run on replay")))
                .block();

        assertThat(first).isInstanceOf(Success.class);
        assertThat(((Success<Void>) replayed).value()).isNull();
        assertThat(store.delegate
                        .data
                        .get(new IdempotencyKey("PROCESS_EVENT", "evt-1"))
                        .resultPayload())
                .isNull();
    }

    @Test
    void missingTransactionIsRejectedWhenRequired() {
        ReactiveTransactionContext inactive = new ReactiveTransactionContext() {
            @Override
            public Mono<Boolean> isActive() {
                return Mono.just(false);
            }

            @Override
            public Mono<Void> afterCommit(Supplier<Mono<Void>> action) {
                return Mono.defer(action);
            }
        };
        DefaultReactiveIdempotencyService service = new DefaultReactiveIdempotencyServiceBuilder(store)
                .clock(clock)
                .transactionContext(inactive)
                .build();

        StepVerifier.create(service.operation(OPERATION)
                        .key(KEY)
                        .request(command)
                        .execute(PaymentResult.class, this::countingAction))
                .verifyError(MissingTransactionException.class);
        assertThat(actionCalls).hasValue(0);
    }

    @Test
    void localCacheHitSkipsDistributedCacheAndPersistence() {
        DefaultReactiveIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();
        IdempotencyRecord record = seedCompletedRecord();
        local.data.put(record.key(), record);

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(actionCalls).hasValue(0);
        assertThat(distributed.delegate.gets).hasValue(0);
        assertThat(store.delegate.findCalls).hasValue(0);
        assertThat(store.delegate.acquireCalls).hasValue(0);
    }

    @Test
    void distributedCacheHitIsPromotedToLocalCache() {
        DefaultReactiveIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();
        IdempotencyRecord record = seedCompletedRecord();
        distributed.delegate.data.put(record.key(), record);

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(actionCalls).hasValue(0);
        assertThat(local.data).containsEntry(record.key(), record);
        assertThat(store.delegate.findCalls).hasValue(0);
    }

    @Test
    void lookupBeforeAcquireReplaysTerminalWithoutAcquire() {
        IdempotencyRecord record = seedCompletedRecord();
        store.delegate.data.put(record.key(), record);
        DefaultReactiveIdempotencyService service =
                serviceBuilder().lookupBeforeAcquire(true).build();

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(actionCalls).hasValue(0);
        assertThat(store.delegate.findCalls).hasValue(1);
        assertThat(store.delegate.acquireCalls).hasValue(0);
    }

    @Test
    void insertFirstSkipsPersistenceFindOnCacheMiss() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(store.delegate.acquireCalls).hasValue(1);
        assertThat(store.delegate.findCalls).hasValue(0);
    }

    @Test
    void ttlOverrideIsWrittenOnAcquire() {
        DefaultReactiveIdempotencyService service =
                serviceBuilder().persistenceTtl(Duration.ofHours(24)).build();

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .ttl(Duration.ofMinutes(5))
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(store.delegate.data.get(new IdempotencyKey(OPERATION, KEY)).expiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void nullTtlOverrideMeansNoExpiry() {
        DefaultReactiveIdempotencyService service =
                serviceBuilder().persistenceTtl(Duration.ofHours(24)).build();

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .ttl(null)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        assertThat(store.delegate.data.get(new IdempotencyKey(OPERATION, KEY)).expiresAt())
                .isNull();
    }

    @Test
    void cachePopulationIsDeferredUntilAfterCommit() {
        List<Supplier<Mono<Void>>> deferred = new ArrayList<>();
        ReactiveTransactionContext capturing = new ReactiveTransactionContext() {
            @Override
            public Mono<Boolean> isActive() {
                return Mono.just(true);
            }

            @Override
            public Mono<Void> afterCommit(Supplier<Mono<Void>> action) {
                deferred.add(action);
                return Mono.empty();
            }
        };
        DefaultReactiveIdempotencyService service = serviceBuilder()
                .localCache(local)
                .distributedCache(distributed)
                .transactionContext(capturing)
                .requireActiveTransaction(false)
                .build();

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        IdempotencyKey key = new IdempotencyKey(OPERATION, KEY);
        assertThat(local.data).isEmpty();
        assertThat(distributed.delegate.data).isEmpty();
        assertThat(deferred).hasSize(1);

        deferred.get(0).get().block();
        assertThat(local.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(distributed.delegate.data.get(key).status()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    void acquireConflictAndWaitAreRecorded() {
        IdempotencyMetrics metrics = mock(IdempotencyMetrics.class);
        IdempotencyRecord record = seedCompletedRecord();
        store.delegate.data.put(record.key(), record);
        DefaultReactiveIdempotencyService service =
                serviceBuilder().metrics(metrics).build();

        service.operation(OPERATION)
                .key(KEY)
                .request(command)
                .execute(PaymentResult.class, this::countingAction)
                .block();

        verify(metrics).acquireConflict();
        verify(metrics).acquireWait(any(Duration.class));
    }

    @Test
    void blankOperationIsRejected() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.operation(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operation");
    }

    @Test
    void executeWithoutKeyErrors() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();
        StepVerifier.create(service.operation(OPERATION).execute(PaymentResult.class, this::countingAction))
                .verifyError(IllegalStateException.class);
    }

    @Test
    void emptyActionMonoIsRejected() {
        DefaultReactiveIdempotencyService service = serviceBuilder().build();
        StepVerifier.create(service.operation(OPERATION)
                        .key(KEY)
                        .request(command)
                        .execute(PaymentResult.class, Mono::empty))
                .verifyError(NullPointerException.class);
    }

    @Test
    void staleProcessingCacheEntryIsEvictedAndFallsThrough() {
        DefaultReactiveIdempotencyService service =
                serviceBuilder().localCache(local).distributedCache(distributed).build();
        IdempotencyRecord processing =
                IdempotencyRecord.processing(new IdempotencyKey(OPERATION, KEY), "stale", NOW, null);
        local.data.put(processing.key(), processing);
        distributed.delegate.data.put(processing.key(), processing);

        StepVerifier.create(service.operation(OPERATION)
                        .key(KEY)
                        .request(command)
                        .execute(PaymentResult.class, this::countingAction))
                .assertNext(result -> assertThat(result.isSuccess()).isTrue())
                .verifyComplete();

        assertThat(actionCalls).hasValue(1);
        assertThat(local.evicts).hasValue(1);
    }

    private IdempotencyRecord seedCompletedRecord() {
        return IdempotencyRecord.processing(
                        new IdempotencyKey(OPERATION, KEY),
                        new com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy().calculate(command),
                        NOW.minusSeconds(60),
                        null)
                .completed(PaymentResult.class.getName(), "{\"paymentId\":\"pay-42\"}", NOW);
    }
}
