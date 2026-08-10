package com.kholodilin.idempotency.model;

/**
 * Persistent status of an idempotency record.
 */
public enum IdempotencyStatus {

    /**
     * The operation has been registered and is currently executing. With the default
     * same-transaction persistence model this status is never visible to other
     * transactions: it commits together with the terminal status.
     */
    PROCESSING,

    /**
     * The business operation finished successfully; its result is stored and replayable.
     */
    COMPLETED,

    /**
     * The business operation finished with a deterministic business rejection;
     * the rejection outcome is stored and replayable.
     */
    REJECTED;

    /**
     * @return {@code true} for {@link #COMPLETED} and {@link #REJECTED}
     */
    public boolean isTerminal() {
        return this != PROCESSING;
    }
}
