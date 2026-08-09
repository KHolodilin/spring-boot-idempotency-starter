package com.kholodilin.idempotency.spi;

import com.kholodilin.idempotency.IdempotencyStatus;

/**
 * Observability hook. A no-op implementation is used unless something better
 * (e.g. Micrometer-based, auto-configured by the starter) is provided.
 */
public interface IdempotencyMetrics {

    IdempotencyMetrics NOOP = new IdempotencyMetrics() {};

    /**
     * A usable record was found at the given lookup level.
     *
     * @param level {@code local}, {@code distributed} or {@code persistence}
     */
    default void lookupHit(String level) {}

    /**
     * A stored outcome was replayed instead of executing the action.
     */
    default void replayed(IdempotencyStatus status) {}

    /**
     * Fingerprint mismatch: the key was reused with a different payload.
     */
    default void conflict() {}

    /**
     * A new record was acquired and the business action is about to run.
     */
    default void acquired() {}

    /**
     * A fresh outcome was persisted.
     */
    default void persisted(IdempotencyStatus status) {}
}
