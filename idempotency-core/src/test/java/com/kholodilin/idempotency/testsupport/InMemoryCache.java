package com.kholodilin.idempotency.testsupport;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.LocalCache;

/**
 * In-memory cache usable as both {@link LocalCache} and {@link DistributedCache} in tests.
 */
public final class InMemoryCache implements LocalCache, DistributedCache {

    public final Map<IdempotencyKey, IdempotencyRecord> data = new HashMap<>();
    public final AtomicInteger gets = new AtomicInteger();
    public final AtomicInteger puts = new AtomicInteger();
    public final AtomicInteger evicts = new AtomicInteger();

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
