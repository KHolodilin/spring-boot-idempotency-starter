package com.kholodilin.idempotency.reactive.autoconfigure;

import java.util.concurrent.atomic.AtomicInteger;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class SpringReactiveTransactionContextTest {

    private final SpringReactiveTransactionContext context = new SpringReactiveTransactionContext();

    @Test
    void isActiveIsFalseOutsideATransaction() {
        StepVerifier.create(context.isActive()).expectNext(false).verifyComplete();
    }

    @Test
    void afterCommitRunsImmediatelyWhenNoTransaction() {
        AtomicInteger runs = new AtomicInteger();

        StepVerifier.create(context.afterCommit(() -> {
                    runs.incrementAndGet();
                    return Mono.empty();
                }))
                .verifyComplete();

        assertThat(runs).hasValue(1);
    }

    @Test
    void afterCommitIsDeferredUntilReactiveTransactionCommits() {
        ConnectionFactory connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///txctx;DB_CLOSE_DELAY=-1");
        TransactionalOperator operator = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        AtomicInteger runs = new AtomicInteger();

        StepVerifier.create(operator.transactional(context.afterCommit(() -> {
                            runs.incrementAndGet();
                            return Mono.empty();
                        })
                        .then(Mono.fromRunnable(() -> assertThat(runs)
                                .as("must not run before commit")
                                .hasValue(0)))))
                .verifyComplete();

        assertThat(runs).hasValue(1);
    }

    @Test
    void isActiveInsideTransactionalOperator() {
        ConnectionFactory connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///txactive;DB_CLOSE_DELAY=-1");
        TransactionalOperator operator = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));

        StepVerifier.create(operator.transactional(context.isActive()))
                .expectNext(true)
                .verifyComplete();
    }
}
