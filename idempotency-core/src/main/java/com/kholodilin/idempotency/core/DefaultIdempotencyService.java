package com.kholodilin.idempotency.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.ExecutionResult.Rejected;
import com.kholodilin.idempotency.ExecutionResult.Success;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.exception.MissingTransactionException;
import com.kholodilin.idempotency.jackson.Json;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.idempotency.spi.TransactionContext;
import org.jspecify.annotations.Nullable;

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
    private final TransactionContext transactionContext;

    DefaultIdempotencyService(DefaultIdempotencyServiceBuilder builder) {
        this.persistenceStore = builder.persistenceStore;
        this.fingerprintStrategy = builder.fingerprintStrategy;
        this.serializer = builder.serializer;
        this.localCache = builder.localCache;
        this.distributedCache = builder.distributedCache;
        this.metrics = builder.metrics;
        this.clock = builder.clock;
        this.persistenceTtl = builder.persistenceTtl;
        this.requireActiveTransaction = builder.requireActiveTransaction;
        this.transactionContext = builder.transactionContext;
    }

    @Override
    public <RQ, RS> ExecutionResult<RS> execute(
            String operation,
            String idempotencyKey,
            @Nullable RQ request,
            Class<RS> resultType,
            Supplier<ExecutionResult<RS>> action) {
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(action, "action");
        IdempotencyKey key = new IdempotencyKey(operation, idempotencyKey);

        if (requireActiveTransaction && !transactionContext.isActive()) {
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

    private <RS> ExecutionResult<RS> runAction(
            IdempotencyKey key,
            String fingerprint,
            Instant createdAt,
            @Nullable Instant expiresAt,
            Class<RS> resultType,
            Supplier<ExecutionResult<RS>> action) {
        // A technical exception propagates from here: the caller's transaction rolls back,
        // no idempotency record and no business changes are committed, retry starts clean.
        ExecutionResult<RS> result = Objects.requireNonNull(
                action.get(),
                "business action must not return null; use ExecutionResult.success(null) for void-like operations");

        Instant completedAt = clock.instant();
        IdempotencyRecord processing = IdempotencyRecord.processing(key, fingerprint, createdAt, expiresAt);

        IdempotencyRecord terminal;
        ExecutionResult<RS> outcome;
        switch (result) {
            case Success<RS> success -> {
                String payload = success.value() == null ? null : serializer.serialize(success.value());
                persistenceStore.complete(key, resultType.getName(), payload, completedAt);
                terminal = processing.completed(resultType.getName(), payload, completedAt);
                outcome = success;
            }
            case Rejected<RS> rejected -> {
                String details =
                        rejected.details().isNull() ? null : rejected.details().toString();
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
            IdempotencyRecord hit = readCache(key, localCache::get, localCache::evict, LEVEL_LOCAL);
            if (hit != null) {
                return hit;
            }
        }
        if (distributedCache != null) {
            IdempotencyRecord hit = readCache(key, distributedCache::get, distributedCache::evict, LEVEL_DISTRIBUTED);
            if (hit != null) {
                if (localCache != null) {
                    localCache.put(key, hit);
                }
                return hit;
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

    private @Nullable IdempotencyRecord readCache(
            IdempotencyKey key,
            Function<IdempotencyKey, Optional<IdempotencyRecord>> get,
            Consumer<IdempotencyKey> evict,
            String level) {
        IdempotencyRecord record = get.apply(key).orElse(null);
        if (record == null) {
            return null;
        }
        if (isUsable(record)) {
            metrics.lookupHit(level);
            return record;
        }
        evict.accept(key);
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
        transactionContext.afterCommit(() -> promote(record));
    }
}
