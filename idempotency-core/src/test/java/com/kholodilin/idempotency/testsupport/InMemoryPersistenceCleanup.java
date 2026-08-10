package com.kholodilin.idempotency.testsupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.spi.IdempotencyPersistenceCleanup;

/**
 * In-memory {@link IdempotencyPersistenceCleanup} backed by {@link InMemoryStore}.
 *
 * <p>Deletes by {@code expires_at} only; does not emulate {@code SKIP LOCKED}.
 */
public final class InMemoryPersistenceCleanup implements IdempotencyPersistenceCleanup {

    private final InMemoryStore store;

    public InMemoryPersistenceCleanup(InMemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public int deleteExpired(Instant before, int limit) {
        Objects.requireNonNull(before, "before");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        synchronized (store) {
            List<IdempotencyKey> victims = new ArrayList<>();
            store.data.values().stream()
                    .filter(r -> r.expiresAt() != null && r.expiresAt().isBefore(before))
                    .sorted(Comparator.comparing(IdempotencyRecord::expiresAt))
                    .limit(limit)
                    .map(IdempotencyRecord::key)
                    .forEach(victims::add);
            victims.forEach(store.data::remove);
            return victims.size();
        }
    }
}
