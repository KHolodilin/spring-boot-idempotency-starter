package com.kholodilin.idempotency.autoconfigure;

import java.time.Clock;
import java.util.Objects;

import com.kholodilin.idempotency.spi.IdempotencyPersistenceCleanup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Periodically deletes expired rows from persistence via {@link IdempotencyPersistenceCleanup}.
 *
 * <p>Invoked by a cron task registered in {@link IdempotencyCleanupAutoConfiguration}.
 */
public final class IdempotencyPersistenceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPersistenceCleanupJob.class);

    private final IdempotencyPersistenceCleanup cleanup;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int batchSize;

    public IdempotencyPersistenceCleanupJob(
            IdempotencyPersistenceCleanup cleanup,
            PlatformTransactionManager transactionManager,
            Clock clock,
            int batchSize) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
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
            deleted = Objects.requireNonNullElse(
                    transactionTemplate.execute(status -> cleanup.deleteExpired(before, batchSize)), 0);
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            log.info("Deleted {} expired idempotency persistence row(s)", total);
        }
    }
}
