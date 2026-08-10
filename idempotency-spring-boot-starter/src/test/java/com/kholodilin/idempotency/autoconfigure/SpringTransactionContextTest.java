package com.kholodilin.idempotency.autoconfigure;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

class SpringTransactionContextTest {

    private final SpringTransactionContext context = new SpringTransactionContext();

    @BeforeEach
    @AfterEach
    void cleanTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void isActiveFollowsSpringTransactionFlag() {
        assertThat(context.isActive()).isFalse();

        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(context.isActive()).isTrue();
    }

    @Test
    void afterCommitRunsImmediatelyWhenSynchronizationInactive() {
        AtomicInteger runs = new AtomicInteger();

        context.afterCommit(runs::incrementAndGet);

        assertThat(runs).hasValue(1);
    }

    @Test
    void afterCommitIsDeferredUntilSynchronizationAfterCommit() {
        AtomicInteger runs = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        context.afterCommit(runs::incrementAndGet);

        assertThat(runs).as("must not run before commit").hasValue(0);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        assertThat(runs).hasValue(1);
    }
}
