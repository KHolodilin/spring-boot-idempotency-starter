package com.kholodilin.idempotency.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.spi.PersistenceStore;
import org.jspecify.annotations.Nullable;

/**
 * In-memory {@link PersistenceStore} for unit and integration tests.
 *
 * <p>When a {@link Clock} is provided, expired records are treated as absent on
 * {@link #find} / {@link #acquire}. Methods are synchronized for concurrent tests.
 */
public final class InMemoryStore implements PersistenceStore {

    public final Map<IdempotencyKey, IdempotencyRecord> data = new HashMap<>();
    public final AtomicInteger findCalls = new AtomicInteger();
    public final AtomicInteger acquireCalls = new AtomicInteger();

    private final @Nullable Clock clock;

    public InMemoryStore() {
        this(null);
    }

    public InMemoryStore(@Nullable Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized Optional<IdempotencyRecord> find(IdempotencyKey key) {
        findCalls.incrementAndGet();
        return Optional.ofNullable(usable(data.get(key)));
    }

    @Override
    public synchronized boolean acquire(
            IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt) {
        acquireCalls.incrementAndGet();
        if (usable(data.get(key)) != null) {
            return false;
        }
        data.put(key, IdempotencyRecord.processing(key, requestHash, createdAt, expiresAt));
        return true;
    }

    @Override
    public synchronized void complete(
            IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt) {
        data.compute(key, (k, r) -> r.completed(resultType, resultPayload, completedAt));
    }

    @Override
    public synchronized void reject(
            IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt) {
        data.compute(key, (k, r) -> r.rejected(errorCode, detailsPayload, completedAt));
    }

    private @Nullable IdempotencyRecord usable(@Nullable IdempotencyRecord record) {
        if (record == null) {
            return null;
        }
        if (clock != null && record.isExpired(clock.instant())) {
            return null;
        }
        return record;
    }
}
