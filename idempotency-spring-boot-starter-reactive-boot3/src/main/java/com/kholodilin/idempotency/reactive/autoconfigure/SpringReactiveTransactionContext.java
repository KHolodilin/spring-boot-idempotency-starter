package com.kholodilin.idempotency.reactive.autoconfigure;

import java.util.function.Supplier;

import com.kholodilin.idempotency.reactive.spi.ReactiveTransactionContext;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

/**
 * Reactor-context {@link ReactiveTransactionContext} backed by Spring's reactive
 * {@link TransactionSynchronizationManager}.
 */
public final class SpringReactiveTransactionContext implements ReactiveTransactionContext {

    @Override
    public Mono<Boolean> isActive() {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .map(TransactionSynchronizationManager::isActualTransactionActive)
                .onErrorReturn(NoTransactionException.class, false);
    }

    @Override
    public Mono<Void> afterCommit(Supplier<Mono<Void>> action) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .flatMap(manager -> {
                    manager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public Mono<Void> afterCommit() {
                            return Mono.defer(action);
                        }
                    });
                    return Mono.<Void>empty();
                })
                .onErrorResume(NoTransactionException.class, ignored -> Mono.defer(action));
    }
}
