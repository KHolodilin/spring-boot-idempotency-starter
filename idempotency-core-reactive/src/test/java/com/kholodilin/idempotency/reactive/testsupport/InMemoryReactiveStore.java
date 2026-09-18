package com.kholodilin.idempotency.reactive.testsupport;

import java.time.Instant;
import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import com.kholodilin.idempotency.testsupport.InMemoryStore;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * {@link ReactivePersistenceStore} wrapping the blocking in-memory store.
 */
public final class InMemoryReactiveStore implements ReactivePersistenceStore {

    public final InMemoryStore delegate = new InMemoryStore();

    @Override
    public Mono<Optional<IdempotencyRecord>> find(IdempotencyKey key) {
        return Mono.fromCallable(() -> delegate.find(key));
    }

    @Override
    public Mono<Boolean> acquire(
            IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt) {
        return Mono.fromCallable(() -> delegate.acquire(key, requestHash, createdAt, expiresAt));
    }

    @Override
    public Mono<Void> complete(
            IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt) {
        return Mono.fromRunnable(() -> delegate.complete(key, resultType, resultPayload, completedAt));
    }

    @Override
    public Mono<Void> reject(
            IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt) {
        return Mono.fromRunnable(() -> delegate.reject(key, errorCode, detailsPayload, completedAt));
    }
}
