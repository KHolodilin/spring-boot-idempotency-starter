package com.kholodilin.idempotency.core;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.IdempotencyConflictException;
import com.kholodilin.idempotency.IdempotencyKey;
import com.kholodilin.idempotency.IdempotencyRecord;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.MissingTransactionException;
import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer;
import com.kholodilin.idempotency.jackson.Json;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Default {@link IdempotencyService} implementation.
 *
 * <p>Lookup order: local cache (if configured) → distributed cache (if configured) →
 * persistence (source of truth). Freshly persisted outcomes are pushed to the cache
 * layers only after the surrounding transaction commits, so caches never contain
 * uncommitted state.
 */
public final class DefaultIdempotencyService implements IdempotencyService {

    static final String LEVEL_LOCAL = "local";
    static final String LEVEL_DISTRIBUTED = "distributed";
    static final String LEVEL_PERSISTENCE = "persistence";

    private static final int MAX_ACQUIRE_ATTEMPTS = 3;

    private final PersistenceStore persistenceStore;
    private final FingerprintStrategy fingerprintStrategy;
    private final IdempotencySerializer serializer;
    private final @Nullable LocalCache localCache;
    private final @Nullable DistributedCache distributedCache;
    private final IdempotencyMetrics metrics;
    private final Clock clock;
    private final @Nullable Duration persistenceTtl;
    private final boolean requireActiveTransaction;

    private DefaultIdempotencyService(Builder builder) {
        this.persistenceStore = builder.persistenceStore;
        this.fingerprintStrategy = builder.fingerprintStrategy;
        this.serializer = builder.serializer;
        this.localCache = builder.localCache;
        this.distributedCache = builder.distributedCache;
        this.metrics = builder.metrics;
        this.clock = builder.clock;
        this.persistenceTtl = builder.persistenceTtl;
        this.requireActiveTransaction = builder.requireActiveTransaction;
    }

    public static Builder builder(PersistenceStore persistenceStore) {
        return new Builder(persistenceStore);
    }

    @Override
    public <RQ, RS> ExecutionResult<RS> execute(String operation,
                                                String idempotencyKey,
                                                @Nullable RQ request,
                                                Class<RS> resultType,
                                                Supplier<ExecutionResult<RS>> action) {
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(action, "action");
        IdempotencyKey key = new IdempotencyKey(operation, idempotencyKey);

        if (requireActiveTransaction && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new MissingTransactionException(key);
        }

        String fingerprint = fingerprintStrategy.calculate(request);

        IdempotencyRecord existing = lookup(key);
        if (existing != null) {
            return replay(existing, fingerprint, resultType);
        }

        for (int attempt = 0; attempt < MAX_ACQUIRE_ATTEMPTS; attempt++) {
            Instant createdAt = clock.instant();
            Instant expiresAt = persistenceTtl == null ? null : createdAt.plus(persistenceTtl);

            // With the JDBC/PostgreSQL store a concurrent duplicate blocks here on the
            // primary-key index until the first transaction commits or rolls back.
            if (persistenceStore.acquire(key, fingerprint, createdAt, expiresAt)) {
                metrics.acquired();
                return runAction(key, fingerprint, createdAt, expiresAt, resultType, action);
            }

            Optional<IdempotencyRecord> found = persistenceStore.find(key);
            if (found.isPresent() && isUsable(found.get())) {
                IdempotencyRecord record = found.get();
                metrics.lookupHit(LEVEL_PERSISTENCE);
                promote(record);
                return replay(record, fingerprint, resultType);
            }
            // The concurrent holder rolled back or the record expired — try to acquire again.
        }
        throw new IllegalStateException(
                "Could not acquire idempotency record for %s after %d attempts".formatted(key, MAX_ACQUIRE_ATTEMPTS));
    }

    private <RS> ExecutionResult<RS> runAction(IdempotencyKey key,
                                               String fingerprint,
                                               Instant createdAt,
                                               @Nullable Instant expiresAt,
                                               Class<RS> resultType,
                                               Supplier<ExecutionResult<RS>> action) {
        // A technical exception propagates from here: the caller's transaction rolls back,
        // no idempotency record and no business changes are committed, retry starts clean.
        ExecutionResult<RS> result = Objects.requireNonNull(action.get(),
                "business action must not return null; use ExecutionResult.success(null) for void-like operations");

        Instant completedAt = clock.instant();
        IdempotencyRecord processing = IdempotencyRecord.processing(key, fingerprint, createdAt, expiresAt);

        IdempotencyRecord terminal;
        ExecutionResult<RS> outcome;
        switch (result) {
            case Success<RS> success -> {
                String payload = success.value() == null ? null
                        : new String(serializer.serialize(success.value()), StandardCharsets.UTF_8);
                persistenceStore.complete(key, resultType.getName(), payload, completedAt);
                terminal = processing.completed(resultType.getName(), payload, completedAt);
                outcome = success;
            }
            case Rejected<RS> rejected -> {
                String details = rejected.details().isNull() ? null : rejected.details().toString();
                persistenceStore.reject(key, rejected.errorCode(), details, completedAt);
                terminal = processing.rejected(rejected.errorCode(), details, completedAt);
                // Normalize the returned tree through the same serialize/parse round trip a
                // replay goes through, so the first execution and a replay are identical
                // (e.g. numeric node types differ between valueToTree and readTree).
                outcome = new Rejected<>(rejected.errorCode(), Json.readTree(details));
            }
        }

        metrics.persisted(terminal.status());
        populateCachesAfterCommit(terminal);
        return outcome;
    }

    private @Nullable IdempotencyRecord lookup(IdempotencyKey key) {
        if (localCache != null) {
            IdempotencyRecord record = localCache.get(key).orElse(null);
            if (record != null) {
                if (isUsable(record)) {
                    metrics.lookupHit(LEVEL_LOCAL);
                    return record;
                }
                localCache.evict(key);
            }
        }
        if (distributedCache != null) {
            IdempotencyRecord record = distributedCache.get(key).orElse(null);
            if (record != null) {
                if (isUsable(record)) {
                    metrics.lookupHit(LEVEL_DISTRIBUTED);
                    if (localCache != null) {
                        localCache.put(key, record);
                    }
                    return record;
                }
                distributedCache.evict(key);
            }
        }
        IdempotencyRecord record = persistenceStore.find(key).orElse(null);
        if (record != null && isUsable(record)) {
            metrics.lookupHit(LEVEL_PERSISTENCE);
            promote(record);
            return record;
        }
        return null;
    }

    private <RS> ExecutionResult<RS> replay(IdempotencyRecord record, String fingerprint, Class<RS> resultType) {
        if (!record.requestHash().equals(fingerprint)) {
            metrics.conflict();
            throw new IdempotencyConflictException(record.key(), record.requestHash(), fingerprint);
        }
        metrics.replayed(record.status());
        return switch (record.status()) {
            case COMPLETED -> {
                RS value = record.resultPayload() == null ? null
                        : serializer.deserialize(record.resultPayload().getBytes(StandardCharsets.UTF_8), resultType);
                yield ExecutionResult.success(value);
            }
            case REJECTED -> new Rejected<>(
                    Objects.requireNonNull(record.errorCode(), "errorCode of a REJECTED record"),
                    Json.readTree(record.resultPayload()));
            case PROCESSING -> throw new IllegalStateException(
                    "Unexpected committed PROCESSING record for " + record.key()
                            + "; with the same-transaction persistence model PROCESSING must never be visible");
        };
    }

    private boolean isUsable(IdempotencyRecord record) {
        return record.status().isTerminal() && !record.isExpired(clock.instant());
    }

    /**
     * Read-path promotion: the record is already committed data, safe to cache immediately.
     */
    private void promote(IdempotencyRecord record) {
        if (distributedCache != null) {
            distributedCache.put(record.key(), record);
        }
        if (localCache != null) {
            localCache.put(record.key(), record);
        }
    }

    /**
     * Write-path population: deferred until after commit so caches never observe
     * uncommitted state.
     */
    private void populateCachesAfterCommit(IdempotencyRecord record) {
        if (localCache == null && distributedCache == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    promote(record);
                }
            });
        }
        else {
            promote(record);
        }
    }

    public static final class Builder {

        private final PersistenceStore persistenceStore;
        private FingerprintStrategy fingerprintStrategy = new CanonicalJsonFingerprintStrategy();
        private IdempotencySerializer serializer = new JacksonIdempotencySerializer();
        private @Nullable LocalCache localCache;
        private @Nullable DistributedCache distributedCache;
        private IdempotencyMetrics metrics = IdempotencyMetrics.NOOP;
        private Clock clock = Clock.systemUTC();
        private @Nullable Duration persistenceTtl;
        private boolean requireActiveTransaction = true;

        private Builder(PersistenceStore persistenceStore) {
            this.persistenceStore = Objects.requireNonNull(persistenceStore, "persistenceStore");
        }

        public Builder fingerprintStrategy(FingerprintStrategy fingerprintStrategy) {
            this.fingerprintStrategy = Objects.requireNonNull(fingerprintStrategy);
            return this;
        }

        public Builder serializer(IdempotencySerializer serializer) {
            this.serializer = Objects.requireNonNull(serializer);
            return this;
        }

        public Builder localCache(@Nullable LocalCache localCache) {
            this.localCache = localCache;
            return this;
        }

        public Builder distributedCache(@Nullable DistributedCache distributedCache) {
            this.distributedCache = distributedCache;
            return this;
        }

        public Builder metrics(IdempotencyMetrics metrics) {
            this.metrics = Objects.requireNonNull(metrics);
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        /**
         * How long a stored outcome stays replayable. {@code null} means no expiration.
         */
        public Builder persistenceTtl(@Nullable Duration persistenceTtl) {
            this.persistenceTtl = persistenceTtl;
            return this;
        }

        /**
         * Disable only in tests: without a surrounding transaction atomicity of business
         * state and idempotency state is not guaranteed.
         */
        public Builder requireActiveTransaction(boolean requireActiveTransaction) {
            this.requireActiveTransaction = requireActiveTransaction;
            return this;
        }

        public DefaultIdempotencyService build() {
            return new DefaultIdempotencyService(this);
        }
    }
}
