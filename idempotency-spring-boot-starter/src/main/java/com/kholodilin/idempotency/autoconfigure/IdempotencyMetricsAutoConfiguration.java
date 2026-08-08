package com.kholodilin.idempotency.autoconfigure;

import com.kholodilin.idempotency.spi.IdempotencyMetrics;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures Micrometer-based idempotency metrics when Micrometer and a
 * {@link MeterRegistry} bean are available.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
public class IdempotencyMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyMetrics.class)
    public MicrometerIdempotencyMetrics micrometerIdempotencyMetrics(MeterRegistry registry) {
        return new MicrometerIdempotencyMetrics(registry);
    }
}
