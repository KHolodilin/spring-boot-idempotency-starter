package com.kholodilin.idempotency.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.idempotency.spi.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Fluent builder for {@link DefaultIdempotencyService}.
 *
 * <p>Only {@link PersistenceStore} is required; everything else has defaults suitable
 * for tests and non-Spring usage.
 */
public final class DefaultIdempotencyServiceBuilder {

    final PersistenceStore persistenceStore;
    FingerprintStrategy fingerprintStrategy = new CanonicalJsonFingerprintStrategy();
    IdempotencySerializer serializer = new JacksonIdempotencySerializer();

    @Nullable LocalCache localCache;

    @Nullable DistributedCache distributedCache;

    IdempotencyMetrics metrics = IdempotencyMetrics.NOOP;
    Clock clock = Clock.systemUTC();

    @Nullable Duration persistenceTtl;

    boolean requireActiveTransaction = true;

    boolean lookupBeforeAcquire = false;

    TransactionContext transactionContext = TransactionContext.IMMEDIATE;

    public DefaultIdempotencyServiceBuilder(PersistenceStore persistenceStore) {
        this.persistenceStore = Objects.requireNonNull(persistenceStore, "persistenceStore");
    }

    public DefaultIdempotencyServiceBuilder fingerprintStrategy(FingerprintStrategy fingerprintStrategy) {
        this.fingerprintStrategy = Objects.requireNonNull(fingerprintStrategy);
        return this;
    }

    public DefaultIdempotencyServiceBuilder serializer(IdempotencySerializer serializer) {
        this.serializer = Objects.requireNonNull(serializer);
        return this;
    }

    public DefaultIdempotencyServiceBuilder localCache(@Nullable LocalCache localCache) {
        this.localCache = localCache;
        return this;
    }

    public DefaultIdempotencyServiceBuilder distributedCache(@Nullable DistributedCache distributedCache) {
        this.distributedCache = distributedCache;
        return this;
    }

    public DefaultIdempotencyServiceBuilder metrics(IdempotencyMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        return this;
    }

    public DefaultIdempotencyServiceBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        return this;
    }

    /**
     * Marker written to {@code expires_at} for physical cleanup jobs. {@code null} means
     * the row is never cleaned up by TTL. Does not affect request-path visibility.
     */
    public DefaultIdempotencyServiceBuilder persistenceTtl(@Nullable Duration persistenceTtl) {
        this.persistenceTtl = persistenceTtl;
        return this;
    }

    /**
     * Disable only in tests: without a surrounding transaction atomicity of business
     * state and idempotency state is not guaranteed.
     */
    public DefaultIdempotencyServiceBuilder requireActiveTransaction(boolean requireActiveTransaction) {
        this.requireActiveTransaction = requireActiveTransaction;
        return this;
    }

    /**
     * When {@code true}, persistence is queried before {@code acquire} on a cache miss
     * (better cold-duplicate latency). Default {@code false}: insert-first.
     */
    public DefaultIdempotencyServiceBuilder lookupBeforeAcquire(boolean lookupBeforeAcquire) {
        this.lookupBeforeAcquire = lookupBeforeAcquire;
        return this;
    }

    public DefaultIdempotencyServiceBuilder transactionContext(TransactionContext transactionContext) {
        this.transactionContext = Objects.requireNonNull(transactionContext);
        return this;
    }

    public DefaultIdempotencyService build() {
        return new DefaultIdempotencyService(this);
    }
}
