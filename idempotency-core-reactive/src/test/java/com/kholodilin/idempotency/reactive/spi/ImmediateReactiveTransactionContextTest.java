package com.kholodilin.idempotency.reactive.spi;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ImmediateReactiveTransactionContextTest {

    private final ImmediateReactiveTransactionContext context = new ImmediateReactiveTransactionContext();

    @Test
    void isAlwaysActive() {
        StepVerifier.create(context.isActive()).expectNext(true).verifyComplete();
    }

    @Test
    void afterCommitRunsImmediately() {
        StepVerifier.create(context.afterCommit(() -> Mono.empty())).verifyComplete();
    }
}
