package com.kholodilin.idempotency.reactive.testsupport;

import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.reactive.spi.ReactiveDistributedCache;
import com.kholodilin.idempotency.testsupport.InMemoryCache;
import reactor.core.publisher.Mono;

/**
 * {@link ReactiveDistributedCache} wrapping the blocking in-memory cache.
 */
public final class InMemoryReactiveDistributedCache implements ReactiveDistributedCache {

    public final InMemoryCache delegate = new InMemoryCache();

    @Override
    public Mono<Optional<IdempotencyRecord>> get(IdempotencyKey key) {
        return Mono.fromCallable(() -> delegate.get(key));
    }

    @Override
    public Mono<Void> put(IdempotencyKey key, IdempotencyRecord record) {
        return Mono.fromRunnable(() -> delegate.put(key, record));
    }

    @Override
    public Mono<Void> evict(IdempotencyKey key) {
        return Mono.fromRunnable(() -> delegate.evict(key));
    }
}
