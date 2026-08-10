package com.kholodilin.idempotency.spi;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImmediateTransactionContextTest {

    @Test
    void isAlwaysActiveAndRunsActionsImmediately() {
        ImmediateTransactionContext context = new ImmediateTransactionContext();
        AtomicInteger calls = new AtomicInteger();

        assertThat(context.isActive()).isTrue();
        context.afterCommit(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
    }
}
