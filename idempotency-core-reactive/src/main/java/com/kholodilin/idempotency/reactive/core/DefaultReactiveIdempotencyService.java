package com.kholodilin.idempotency.reactive.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.exception.MissingTransactionException;
import com.kholodilin.idempotency.jackson.Json;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.reactive.ReactiveIdempotencyCall;
import com.kholodilin.idempotency.reactive.ReactiveIdempotencyService;
import com.kholodilin.idempotency.reactive.spi.ReactiveDistributedCache;
import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import com.kholodilin.idempotency.reactive.spi.ReactiveTransactionContext;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Default {@link ReactiveIdempotencyService} implementation.
 *
 * <p>Lookup order: local cache (if configured) → distributed cache (if configured).
 * On a cache miss the service either optionally checks persistence or goes straight to
 * {@code INSERT ... ON CONFLICT DO NOTHING}. Fresh outcomes are pushed to the cache
 * layers only after the surrounding transaction commits.
 */
public final class DefaultReactiveIdempotencyService implements ReactiveIdempotencyService {

    static final String LEVEL_LOCAL = "local";
    static final String LEVEL_DISTRIBUTED = "distributed";
    static final String LEVEL_PERSISTENCE = "persistence";

    private static final int MAX_ACQUIRE_ATTEMPTS = 3;

    private final ReactivePersistenceStore persistenceStore;
    private final FingerprintStrategy fingerprintStrategy;
    private final IdempotencySerializer serializer;
    private final @Nullable LocalCache localCache;
    private final @Nullable ReactiveDistributedCache distributedCache;
    private final IdempotencyMetrics metrics;
    private final Clock clock;
    private final @Nullable Duration persistenceTtl;
    private final boolean requireActiveTransaction;
    private final boolean lookupBeforeAcquire;
    private final ReactiveTransactionContext transactionContext;

    DefaultReactiveIdempotencyService(DefaultReactiveIdempotencyServiceBuilder builder) {
        this.persistenceStore = builder.persistenceStore;
        this.fingerprintStrategy = builder.fingerprintStrategy;
        this.serializer = builder.serializer;
        this.localCache = builder.localCache;
        this.distributedCache = builder.distributedCache;
        this.metrics = builder.metrics;
        this.clock = builder.clock;
        this.persistenceTtl = builder.persistenceTtl;
        this.requireActiveTransaction = builder.requireActiveTransaction;
        this.lookupBeforeAcquire = builder.lookupBeforeAcquire;
        this.transactionContext = builder.transactionContext;
    }

    @Override
    public ReactiveIdempotencyCall operation(String operation) {
        return new Call(operation);
    }

    <RQ, RS> Mono<ExecutionResult<RS>> executeInternal(
            String operation,
            String idempotencyKey,
            @Nullable RQ request,
            Class<RS> resultType,
            Supplier<Mono<ExecutionResult<RS>>> action,
            boolean ttlSet,
            @Nullable Duration ttlOverride) {
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(action, "action");
        IdempotencyKey key = new IdempotencyKey(operation, idempotencyKey);

        Mono<ExecutionResult<RS>> pipeline =
                Mono.defer(() -> doExecute(key, request, resultType, action, ttlSet, ttlOverride));
        if (!requireActiveTransaction) {
            return pipeline;
        }
        return transactionContext.isActive().flatMap(active -> {
            if (!active) {
                return Mono.error(new MissingTransactionException(key));
            }
            return pipeline;
        });
    }

    private <RQ, RS> Mono<ExecutionResult<RS>> doExecute(
            IdempotencyKey key,
            @Nullable RQ request,
            Class<RS> resultType,
            Supplier<Mono<ExecutionResult<RS>>> action,
            boolean ttlSet,
            @Nullable Duration ttlOverride) {
        String fingerprint = fingerprintStrategy.calculate(request);
        Duration effectiveTtl = ttlSet ? ttlOverride : persistenceTtl;

        return lookupCaches(key)
                .flatMap(cached -> Mono.fromCallable(() -> replay(cached, fingerprint, resultType)))
                .switchIfEmpty(Mono.defer(() -> {
                    if (!lookupBeforeAcquire) {
                        return acquireLoop(key, fingerprint, resultType, action, effectiveTtl, 0);
                    }
                    return persistenceStore.find(key).flatMap(existing -> {
                        if (existing.isPresent() && isUsable(existing.get())) {
                            metrics.lookupHit(LEVEL_PERSISTENCE);
                            IdempotencyRecord record = existing.get();
                            return promote(record)
                                    .then(Mono.fromCallable(() -> replay(record, fingerprint, resultType)));
                        }
                        return acquireLoop(key, fingerprint, resultType, action, effectiveTtl, 0);
                    });
                }));
    }

    private <RS> Mono<ExecutionResult<RS>> acquireLoop(
            IdempotencyKey key,
            String fingerprint,
            Class<RS> resultType,
            Supplier<Mono<ExecutionResult<RS>>> action,
            @Nullable Duration effectiveTtl,
            int attempt) {
        if (attempt >= MAX_ACQUIRE_ATTEMPTS) {
            return Mono.error(new IllegalStateException("Could not acquire idempotency record for %s after %d attempts"
                    .formatted(key, MAX_ACQUIRE_ATTEMPTS)));
        }

        Instant createdAt = clock.instant();
        Instant expiresAt = effectiveTtl == null ? null : createdAt.plus(effectiveTtl);
        Instant acquireStarted = clock.instant();

        return persistenceStore.acquire(key, fingerprint, createdAt, expiresAt).flatMap(acquired -> {
            metrics.acquireWait(Duration.between(acquireStarted, clock.instant()));
            if (acquired) {
                metrics.acquired();
                return runAction(key, fingerprint, createdAt, expiresAt, resultType, action);
            }
            metrics.acquireConflict();
            return persistenceStore.find(key).flatMap(found -> {
                if (found.isPresent() && isUsable(found.get())) {
                    IdempotencyRecord record = found.get();
                    metrics.lookupHit(LEVEL_PERSISTENCE);
                    return promote(record).then(Mono.fromCallable(() -> replay(record, fingerprint, resultType)));
                }
                return acquireLoop(key, fingerprint, resultType, action, effectiveTtl, attempt + 1);
            });
        });
    }

    private <RS> Mono<ExecutionResult<RS>> runAction(
            IdempotencyKey key,
            String fingerprint,
            Instant createdAt,
            @Nullable Instant expiresAt,
            Class<RS> resultType,
            Supplier<Mono<ExecutionResult<RS>>> action) {
        return Mono.defer(action)
                .switchIfEmpty(
                        Mono.error(
                                new NullPointerException(
                                        "business action must not return empty; use ExecutionResult.success(null) for void-like operations")))
                .flatMap(result -> persistOutcome(key, fingerprint, createdAt, expiresAt, resultType, result));
    }

    private <RS> Mono<ExecutionResult<RS>> persistOutcome(
            IdempotencyKey key,
            String fingerprint,
            Instant createdAt,
            @Nullable Instant expiresAt,
            Class<RS> resultType,
            ExecutionResult<RS> result) {
        Objects.requireNonNull(
                result,
                "business action must not return null; use ExecutionResult.success(null) for void-like operations");

        Instant completedAt = clock.instant();
        IdempotencyRecord processing = IdempotencyRecord.processing(key, fingerprint, createdAt, expiresAt);

        return switch (result) {
            case Success<RS> success -> {
                String payload = success.value() == null ? null : serializer.serialize(success.value());
                IdempotencyRecord terminal = processing.completed(resultType.getName(), payload, completedAt);
                yield persistenceStore
                        .complete(key, resultType.getName(), payload, completedAt)
                        .then(Mono.defer(() -> {
                            metrics.persisted(terminal.status());
                            return populateCachesAfterCommit(terminal).thenReturn(success);
                        }));
            }
            case Rejected<RS> rejected -> {
                String details =
                        rejected.details().isNull() ? null : rejected.details().toString();
                IdempotencyRecord terminal = processing.rejected(rejected.errorCode(), details, completedAt);
                ExecutionResult<RS> outcome = new Rejected<>(rejected.errorCode(), Json.readTree(details));
                yield persistenceStore
                        .reject(key, rejected.errorCode(), details, completedAt)
                        .then(Mono.defer(() -> {
                            metrics.persisted(terminal.status());
                            return populateCachesAfterCommit(terminal).thenReturn(outcome);
                        }));
            }
        };
    }

    private Mono<IdempotencyRecord> lookupCaches(IdempotencyKey key) {
        IdempotencyRecord localHit = readLocal(key);
        if (localHit != null) {
            return Mono.just(localHit);
        }
        if (distributedCache == null) {
            return Mono.empty();
        }
        return distributedCache.get(key).flatMap(optional -> {
            if (optional.isEmpty()) {
                return Mono.empty();
            }
            IdempotencyRecord record = optional.get();
            if (!isUsable(record)) {
                return distributedCache.evict(key).then(Mono.empty());
            }
            metrics.lookupHit(LEVEL_DISTRIBUTED);
            if (localCache != null) {
                localCache.put(key, record);
            }
            return Mono.just(record);
        });
    }

    private @Nullable IdempotencyRecord readLocal(IdempotencyKey key) {
        if (localCache == null) {
            return null;
        }
        IdempotencyRecord record = localCache.get(key).orElse(null);
        if (record == null) {
            return null;
        }
        if (isUsable(record)) {
            metrics.lookupHit(LEVEL_LOCAL);
            return record;
        }
        localCache.evict(key);
        return null;
    }

    private <RS> ExecutionResult<RS> replay(IdempotencyRecord record, String fingerprint, Class<RS> resultType) {
        if (!record.requestHash().equals(fingerprint)) {
            metrics.conflict();
            throw new IdempotencyConflictException(record.key(), record.requestHash(), fingerprint);
        }
        metrics.replayed(record.status());
        return switch (record.status()) {
            case COMPLETED -> ExecutionResult.success(serializer.deserialize(record.resultPayload(), resultType));
            case REJECTED ->
                new Rejected<>(
                        Objects.requireNonNull(record.errorCode(), "errorCode of a REJECTED record"),
                        Json.readTree(record.resultPayload()));
            case PROCESSING ->
                throw new IllegalStateException("Unexpected committed PROCESSING record for " + record.key()
                        + "; with the same-transaction persistence model PROCESSING must never be visible");
        };
    }

    private boolean isUsable(IdempotencyRecord record) {
        return record.status().isTerminal();
    }

    private Mono<Void> promote(IdempotencyRecord record) {
        Mono<Void> distributed = distributedCache == null ? Mono.empty() : distributedCache.put(record.key(), record);
        return distributed.doOnSuccess(ignored -> {
            if (localCache != null) {
                localCache.put(record.key(), record);
            }
        });
    }

    private Mono<Void> populateCachesAfterCommit(IdempotencyRecord record) {
        if (localCache == null && distributedCache == null) {
            return Mono.empty();
        }
        return transactionContext.afterCommit(() -> promote(record));
    }

    private final class Call implements ReactiveIdempotencyCall {

        private final String operation;
        private @Nullable String idempotencyKey;
        private @Nullable Object request;
        private boolean ttlSet;
        private @Nullable Duration ttl;

        private Call(String operation) {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("operation must not be blank");
            }
            this.operation = operation;
        }

        @Override
        public ReactiveIdempotencyCall key(String idempotencyKey) {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey must not be blank");
            }
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        @Override
        public <RQ> ReactiveIdempotencyCall request(@Nullable RQ request) {
            this.request = request;
            return this;
        }

        @Override
        public ReactiveIdempotencyCall ttl(@Nullable Duration ttl) {
            this.ttlSet = true;
            this.ttl = ttl;
            return this;
        }

        @Override
        public <RS> Mono<ExecutionResult<RS>> execute(
                Class<RS> resultType, Supplier<Mono<ExecutionResult<RS>>> action) {
            if (idempotencyKey == null) {
                return Mono.error(
                        new IllegalStateException("idempotency key must be set via key(...) before execute(...)"));
            }
            return executeInternal(operation, idempotencyKey, request, resultType, action, ttlSet, ttl);
        }
    }
}
