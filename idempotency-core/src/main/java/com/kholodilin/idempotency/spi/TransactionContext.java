package com.kholodilin.idempotency.spi;

/**
 * Abstraction over the surrounding unit of work.
 *
 * <p>Core uses this to (1) require an active transaction and (2) defer cache writes
 * until after a successful commit. Spring Boot wiring provides a Spring-TX backed
 * implementation; tests and non-Spring usage typically use {@link #IMMEDIATE}.
 */
public interface TransactionContext {

    /**
     * Runs {@code action} immediately. {@link #isActive()} always returns {@code true},
     * so the active-transaction guard is a no-op.
     */
    TransactionContext IMMEDIATE = new ImmediateTransactionContext();

    /**
     * Whether a transaction / unit of work is currently active.
     */
    boolean isActive();

    /**
     * Schedules {@code action} to run after a successful commit when a transaction
     * is active; otherwise runs it immediately.
     */
    void afterCommit(Runnable action);
}
