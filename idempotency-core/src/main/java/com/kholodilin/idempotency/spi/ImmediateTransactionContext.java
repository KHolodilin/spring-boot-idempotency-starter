package com.kholodilin.idempotency.spi;

/**
 * Non-transactional {@link TransactionContext}: actions run immediately and
 * {@link #isActive()} is always {@code true}. Suitable for unit tests and
 * non-Spring assembly.
 */
public final class ImmediateTransactionContext implements TransactionContext {

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void afterCommit(Runnable action) {
        action.run();
    }
}
