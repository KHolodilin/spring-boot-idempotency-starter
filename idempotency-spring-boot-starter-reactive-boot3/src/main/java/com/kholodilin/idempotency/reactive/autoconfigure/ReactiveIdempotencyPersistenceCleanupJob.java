package com.kholodilin.idempotency.reactive.autoconfigure;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.BiFunction;

import com.kholodilin.idempotency.r2dbc.R2dbcIdempotencyPersistenceCleanup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Periodically deletes expired rows from persistence via {@link R2dbcIdempotencyPersistenceCleanup}.
 *
 * <p>Invoked by a cron task registered in {@link IdempotencyCleanupAutoConfiguration}.
 */
public final class ReactiveIdempotencyPersistenceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ReactiveIdempotencyPersistenceCleanupJob.class);

    private final BiFunction<Instant, Integer, Mono<Integer>> deleteExpired;
    private final TransactionalOperator transactionalOperator;
    private final Clock clock;
    private final int batchSize;

    public ReactiveIdempotencyPersistenceCleanupJob(
            R2dbcIdempotencyPersistenceCleanup cleanup,
            TransactionalOperator transactionalOperator,
            Clock clock,
            int batchSize) {
        this(Objects.requireNonNull(cleanup, "cleanup")::deleteExpired, transactionalOperator, clock, batchSize);
    }

    /**
     * Test-friendly constructor; production wiring uses
     * {@link #ReactiveIdempotencyPersistenceCleanupJob(R2dbcIdempotencyPersistenceCleanup, TransactionalOperator, Clock, int)}.
     */
    ReactiveIdempotencyPersistenceCleanupJob(
            BiFunction<Instant, Integer, Mono<Integer>> deleteExpired,
            TransactionalOperator transactionalOperator,
            Clock clock,
            int batchSize) {
        this.deleteExpired = Objects.requireNonNull(deleteExpired, "deleteExpired");
        this.transactionalOperator = Objects.requireNonNull(transactionalOperator, "transactionalOperator");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    public void deleteExpired() {
        var before = clock.instant();
        int total = 0;
        int deleted;
        do {
            Integer batch = transactionalOperator
                    .transactional(deleteExpired.apply(before, batchSize))
                    .block();
            deleted = batch == null ? 0 : batch;
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            log.info("Deleted {} expired idempotency persistence row(s)", total);
        }
    }
}
