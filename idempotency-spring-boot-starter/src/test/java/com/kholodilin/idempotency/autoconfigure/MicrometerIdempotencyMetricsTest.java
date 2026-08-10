package com.kholodilin.idempotency.autoconfigure;

import java.time.Duration;

import com.kholodilin.idempotency.model.IdempotencyStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerIdempotencyMetricsTest {

    @Test
    void recordsAllMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerIdempotencyMetrics metrics = new MicrometerIdempotencyMetrics(registry);

        metrics.lookupHit("persistence");
        metrics.replayed(IdempotencyStatus.COMPLETED);
        metrics.conflict();
        metrics.acquired();
        metrics.acquireConflict();
        metrics.acquireWait(Duration.ofMillis(12));
        metrics.persisted(IdempotencyStatus.REJECTED);

        assertThat(registry.counter("idempotency.lookup.hits", "level", "persistence")
                        .count())
                .isEqualTo(1);
        assertThat(registry.counter("idempotency.replays", "status", "COMPLETED")
                        .count())
                .isEqualTo(1);
        assertThat(registry.counter("idempotency.conflicts").count()).isEqualTo(1);
        assertThat(registry.counter("idempotency.acquired").count()).isEqualTo(1);
        assertThat(registry.counter("idempotency.acquire.conflicts").count()).isEqualTo(1);
        assertThat(registry.timer("idempotency.acquire.wait").count()).isEqualTo(1);
        assertThat(registry.counter("idempotency.persisted", "status", "REJECTED")
                        .count())
                .isEqualTo(1);
    }
}
