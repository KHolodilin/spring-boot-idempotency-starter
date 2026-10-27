package com.kholodilin.idempotency.autoconfigure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.spi.IdempotencyPersistenceCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IdempotencyPersistenceCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new IdempotencyPersistenceCleanupJob(
                        mock(IdempotencyPersistenceCleanup.class),
                        mock(PlatformTransactionManager.class),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void deletesInBatchesUntilPartialBatch() {
        AtomicInteger calls = new AtomicInteger();
        IdempotencyPersistenceCleanup cleanup = (before, limit) -> {
            assertThat(before).isEqualTo(NOW);
            assertThat(limit).isEqualTo(2);
            return calls.incrementAndGet() == 1 ? 2 : 1;
        };
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        IdempotencyPersistenceCleanupJob job =
                new IdempotencyPersistenceCleanupJob(cleanup, tm, Clock.fixed(NOW, ZoneOffset.UTC), 2);

        job.deleteExpired();

        assertThat(calls).hasValue(2);
    }

    @Test
    void noOpWhenNothingDeleted() {
        AtomicInteger calls = new AtomicInteger();
        IdempotencyPersistenceCleanup cleanup = (before, limit) -> {
            calls.incrementAndGet();
            return 0;
        };
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        new IdempotencyPersistenceCleanupJob(cleanup, tm, Clock.fixed(NOW, ZoneOffset.UTC), 10).deleteExpired();

        assertThat(calls).hasValue(1);
    }
}
