package com.kholodilin.idempotency.spi;

import java.time.Instant;

/**
 * Physically deletes expired idempotency rows from the persistence store.
 *
 * <p>Not part of the request path; used by scheduled or ops jobs only. Cache layers
 * are out of scope — they expire independently.
 */
public interface IdempotencyPersistenceCleanup {

    /**
     * Deletes up to {@code limit} rows with {@code expires_at} strictly before
     * {@code before}.
     *
     * @return number of rows physically deleted in this batch
     */
    int deleteExpired(Instant before, int limit);
}
