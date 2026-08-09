package com.kholodilin.idempotency.autoconfigure;

import java.util.Objects;

import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Micrometer-based {@link IdempotencyMetrics}.
 *
 * <p>Meters:
 * <ul>
 *   <li>{@code idempotency.lookup.hits} (tag {@code level}: local/distributed/persistence)</li>
 *   <li>{@code idempotency.replays} (tag {@code status})</li>
 *   <li>{@code idempotency.conflicts}</li>
 *   <li>{@code idempotency.acquired}</li>
 *   <li>{@code idempotency.persisted} (tag {@code status})</li>
 * </ul>
 */
public final class MicrometerIdempotencyMetrics implements IdempotencyMetrics {

    private final MeterRegistry registry;
    private final Counter conflicts;
    private final Counter acquired;

    public MicrometerIdempotencyMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.conflicts = registry.counter("idempotency.conflicts");
        this.acquired = registry.counter("idempotency.acquired");
    }

    @Override
    public void lookupHit(String level) {
        registry.counter("idempotency.lookup.hits", "level", level).increment();
    }

    @Override
    public void replayed(IdempotencyStatus status) {
        registry.counter("idempotency.replays", "status", status.name()).increment();
    }

    @Override
    public void conflict() {
        conflicts.increment();
    }

    @Override
    public void acquired() {
        acquired.increment();
    }

    @Override
    public void persisted(IdempotencyStatus status) {
        registry.counter("idempotency.persisted", "status", status.name()).increment();
    }
}
