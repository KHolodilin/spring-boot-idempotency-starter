package com.kholodilin.idempotency.reactive.core;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer;
import com.kholodilin.idempotency.reactive.spi.ReactiveDistributedCache;
import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import com.kholodilin.idempotency.reactive.spi.ReactiveTransactionContext;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import org.jspecify.annotations.Nullable;

/**
 * Fluent builder for {@link DefaultReactiveIdempotencyService}.
 *
 * <p>Only {@link ReactivePersistenceStore} is required; everything else has defaults
 * suitable for tests and non-Spring usage.
 */
public final class DefaultReactiveIdempotencyServiceBuilder {

    final ReactivePersistenceStore persistenceStore;
    FingerprintStrategy fingerprintStrategy = new CanonicalJsonFingerprintStrategy();
    IdempotencySerializer serializer = new JacksonIdempotencySerializer();

    @Nullable LocalCache localCache;

    @Nullable ReactiveDistributedCache distributedCache;

    IdempotencyMetrics metrics = IdempotencyMetrics.NOOP;
    Clock clock = Clock.systemUTC();

    @Nullable Duration persistenceTtl;

    boolean requireActiveTransaction = true;

    boolean lookupBeforeAcquire = false;

    ReactiveTransactionContext transactionContext = ReactiveTransactionContext.IMMEDIATE;

    public DefaultReactiveIdempotencyServiceBuilder(ReactivePersistenceStore persistenceStore) {
        this.persistenceStore = Objects.requireNonNull(persistenceStore, "persistenceStore");
    }

    public DefaultReactiveIdempotencyServiceBuilder fingerprintStrategy(FingerprintStrategy fingerprintStrategy) {
        this.fingerprintStrategy = Objects.requireNonNull(fingerprintStrategy);
        return this;
    }

    public DefaultReactiveIdempotencyServiceBuilder serializer(IdempotencySerializer serializer) {
        this.serializer = Objects.requireNonNull(serializer);
        return this;
    }

    public DefaultReactiveIdempotencyServiceBuilder localCache(@Nullable LocalCache localCache) {
        this.localCache = localCache;
        return this;
    }

    public DefaultReactiveIdempotencyServiceBuilder distributedCache(
            @Nullable ReactiveDistributedCache distributedCache) {
        this.distributedCache = distributedCache;
        return this;
    }

    public DefaultReactiveIdempotencyServiceBuilder metrics(IdempotencyMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        return this;
    }

    public DefaultReactiveIdempotencyServiceBuilder clock(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        return this;
    }

    /**
     * Marker written to {@code expires_at} for physical cleanup jobs. {@code null} means
     * the row is never cleaned up by TTL. Does not affect request-path visibility.
     */
    public DefaultReactiveIdempotencyServiceBuilder persistenceTtl(@Nullable Duration persistenceTtl) {
        this.persistenceTtl = persistenceTtl;
        return this;
    }

    /**
     * Disable only in tests: without a surrounding transaction atomicity of business
     * state and idempotency state is not guaranteed.
     */
    public DefaultReactiveIdempotencyServiceBuilder requireActiveTransaction(boolean requireActiveTransaction) {
        this.requireActiveTransaction = requireActiveTransaction;
        return this;
    }

    /**
     * When {@code true}, persistence is queried before {@code acquire} on a cache miss
     * (better cold-duplicate latency). Default {@code false}: insert-first.
     */
    public DefaultReactiveIdempotencyServiceBuilder lookupBeforeAcquire(boolean lookupBeforeAcquire) {
        this.lookupBeforeAcquire = lookupBeforeAcquire;
        return this;
    }

    public DefaultReactiveIdempotencyServiceBuilder transactionContext(ReactiveTransactionContext transactionContext) {
        this.transactionContext = Objects.requireNonNull(transactionContext);
        return this;
    }

    public DefaultReactiveIdempotencyService build() {
        return new DefaultReactiveIdempotencyService(this);
    }
}
