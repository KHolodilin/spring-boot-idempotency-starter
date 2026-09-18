package com.kholodilin.idempotency.reactive.autoconfigure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.r2dbc.R2dbcIdempotencyPersistenceCleanup;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReactiveIdempotencyPersistenceCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new ReactiveIdempotencyPersistenceCleanupJob(
                        mock(R2dbcIdempotencyPersistenceCleanup.class),
                        mock(TransactionalOperator.class),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void deletesInBatchesUntilPartialBatch() {
        AtomicInteger calls = new AtomicInteger();
        R2dbcIdempotencyPersistenceCleanup cleanup = mock(R2dbcIdempotencyPersistenceCleanup.class);
        when(cleanup.deleteExpired(any(), anyInt())).thenAnswer(invocation -> {
            assertThat((Instant) invocation.getArgument(0)).isEqualTo(NOW);
            assertThat((Integer) invocation.getArgument(1)).isEqualTo(2);
            return Mono.just(calls.incrementAndGet() == 1 ? 2 : 1);
        });

        TransactionalOperator operator = mock(TransactionalOperator.class);
        when(operator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReactiveIdempotencyPersistenceCleanupJob job =
                new ReactiveIdempotencyPersistenceCleanupJob(cleanup, operator, Clock.fixed(NOW, ZoneOffset.UTC), 2);

        job.deleteExpired();

        assertThat(calls).hasValue(2);
    }

    @Test
    void noOpWhenNothingDeleted() {
        AtomicInteger calls = new AtomicInteger();
        R2dbcIdempotencyPersistenceCleanup cleanup = mock(R2dbcIdempotencyPersistenceCleanup.class);
        when(cleanup.deleteExpired(any(), anyInt())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            return Mono.just(0);
        });

        TransactionalOperator operator = mock(TransactionalOperator.class);
        when(operator.transactional(any(Mono.class))).thenAnswer(invocation -> invocation.getArgument(0));

        new ReactiveIdempotencyPersistenceCleanupJob(cleanup, operator, Clock.fixed(NOW, ZoneOffset.UTC), 10)
                .deleteExpired();

        assertThat(calls).hasValue(1);
    }
}
