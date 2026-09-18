package com.kholodilin.idempotency.reactive.spi;

import java.util.function.Supplier;

import reactor.core.publisher.Mono;

/**
 * Abstraction over the surrounding reactive unit of work.
 *
 * <p>Used to (1) require an active R2DBC transaction and (2) defer cache writes
 * until after a successful commit.
 */
public interface ReactiveTransactionContext {

    /**
     * Runs after-commit actions immediately. {@link #isActive()} always emits
     * {@code true}, so the active-transaction guard is a no-op.
     */
    ReactiveTransactionContext IMMEDIATE = new ImmediateReactiveTransactionContext();

    /**
     * Whether a reactive transaction / unit of work is currently active.
     */
    Mono<Boolean> isActive();

    /**
     * Schedules {@code action} to run after a successful commit when a transaction
     * is active; otherwise runs it immediately.
     */
    Mono<Void> afterCommit(Supplier<Mono<Void>> action);
}
