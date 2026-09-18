package com.kholodilin.idempotency.reactive.spi;

import java.util.function.Supplier;

import reactor.core.publisher.Mono;

/**
 * Non-transactional {@link ReactiveTransactionContext}: after-commit actions run
 * immediately and {@link #isActive()} always emits {@code true}.
 */
public final class ImmediateReactiveTransactionContext implements ReactiveTransactionContext {

    @Override
    public Mono<Boolean> isActive() {
        return Mono.just(true);
    }

    @Override
    public Mono<Void> afterCommit(Supplier<Mono<Void>> action) {
        return Mono.defer(action);
    }
}
