package com.kholodilin.idempotency.reactive.spi;

import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import reactor.core.publisher.Mono;

/**
 * Optional L2 (shared between application instances) reactive cache of committed
 * idempotency records.
 *
 * <p>Only terminal, committed records are ever stored. Fail-open on cache
 * infrastructure errors is the recommended default.
 */
public interface ReactiveDistributedCache {

    Mono<Optional<IdempotencyRecord>> get(IdempotencyKey key);

    Mono<Void> put(IdempotencyKey key, IdempotencyRecord record);

    Mono<Void> evict(IdempotencyKey key);
}
