package com.kholodilin.idempotency.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.spi.PersistenceStore;
import org.jspecify.annotations.Nullable;

/**
 * In-memory {@link PersistenceStore} for unit and integration tests.
 *
 * <p>Rows remain visible until physically removed (no TTL filter on find/acquire).
 * Methods are synchronized for concurrent tests.
 */
public final class InMemoryStore implements PersistenceStore {

    public final Map<IdempotencyKey, IdempotencyRecord> data = new HashMap<>();
    public final AtomicInteger findCalls = new AtomicInteger();
    public final AtomicInteger acquireCalls = new AtomicInteger();

    public InMemoryStore() {}

    /**
     * @param clock ignored; retained for call-site compatibility
     */
    public InMemoryStore(@Nullable Clock clock) {}

    @Override
    public synchronized Optional<IdempotencyRecord> find(IdempotencyKey key) {
        findCalls.incrementAndGet();
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public synchronized boolean acquire(
            IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt) {
        acquireCalls.incrementAndGet();
        if (data.containsKey(key)) {
            return false;
        }
        data.put(key, IdempotencyRecord.processing(key, requestHash, createdAt, expiresAt));
        return true;
    }

    @Override
    public synchronized void complete(
            IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt) {
        IdempotencyRecord current = data.get(key);
        if (current == null || current.status() != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Idempotency complete for %s affected 0 rows, expected exactly 1".formatted(key));
        }
        data.put(key, current.completed(resultType, resultPayload, completedAt));
    }

    @Override
    public synchronized void reject(
            IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt) {
        IdempotencyRecord current = data.get(key);
        if (current == null || current.status() != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException(
                    "Idempotency reject for %s affected 0 rows, expected exactly 1".formatted(key));
        }
        data.put(key, current.rejected(errorCode, detailsPayload, completedAt));
    }
}
