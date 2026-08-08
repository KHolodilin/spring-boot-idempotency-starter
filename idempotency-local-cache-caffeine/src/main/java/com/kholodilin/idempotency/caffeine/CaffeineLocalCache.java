package com.kholodilin.idempotency.caffeine;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.kholodilin.idempotency.IdempotencyKey;
import com.kholodilin.idempotency.IdempotencyRecord;
import com.kholodilin.idempotency.spi.LocalCache;

import org.jspecify.annotations.Nullable;

/**
 * {@link LocalCache} backed by Caffeine.
 *
 * <p>Purpose: low latency replays, hot-key protection, reduced load on the distributed
 * cache and on PostgreSQL. Only committed terminal records are ever stored here by the
 * idempotency service.
 */
public final class CaffeineLocalCache implements LocalCache {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    public static final long DEFAULT_MAX_SIZE = 10_000;

    private final Cache<IdempotencyKey, IdempotencyRecord> cache;

    public CaffeineLocalCache() {
        this(DEFAULT_TTL, DEFAULT_MAX_SIZE, false);
    }

    public CaffeineLocalCache(Duration ttl, long maxSize, boolean recordStatistics) {
        this(ttl, maxSize, recordStatistics, null);
    }

    /**
     * @param ticker custom time source, intended for tests; {@code null} for system time
     */
    public CaffeineLocalCache(Duration ttl, long maxSize, boolean recordStatistics, @Nullable Ticker ticker) {
        Objects.requireNonNull(ttl, "ttl");
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize);
        if (recordStatistics) {
            builder.recordStats();
        }
        if (ticker != null) {
            builder.ticker(ticker);
        }
        this.cache = builder.build();
    }

    @Override
    public Optional<IdempotencyRecord> get(IdempotencyKey key) {
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    @Override
    public void put(IdempotencyKey key, IdempotencyRecord record) {
        cache.put(key, record);
    }

    @Override
    public void evict(IdempotencyKey key) {
        cache.invalidate(key);
    }

    /**
     * Caffeine statistics; all-zero unless the cache was built with statistics recording.
     */
    public CacheStats statistics() {
        return cache.stats();
    }

    /**
     * Estimated number of entries (after pending maintenance).
     */
    public long estimatedSize() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
