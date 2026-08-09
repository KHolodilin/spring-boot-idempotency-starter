package com.kholodilin.idempotency.autoconfigure;

import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyService;
import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Core auto-configuration: default {@link FingerprintStrategy}, {@link IdempotencySerializer}
 * and the {@link IdempotencyService} itself. Every default backs off when the application
 * provides its own bean of the same SPI type.
 */
@AutoConfiguration(
        after = {
            IdempotencyJdbcAutoConfiguration.class,
            IdempotencyCaffeineAutoConfiguration.class,
            IdempotencyRedisAutoConfiguration.class,
            IdempotencyMetricsAutoConfiguration.class
        })
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FingerprintStrategy idempotencyFingerprintStrategy(IdempotencyProperties properties) {
        return new CanonicalJsonFingerprintStrategy(properties.getFingerprint().getAlgorithm());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencySerializer idempotencySerializer() {
        return new JacksonIdempotencySerializer();
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyService.class)
    @ConditionalOnBean(PersistenceStore.class)
    public IdempotencyService idempotencyService(
            IdempotencyProperties properties,
            PersistenceStore persistenceStore,
            FingerprintStrategy fingerprintStrategy,
            IdempotencySerializer serializer,
            ObjectProvider<LocalCache> localCache,
            ObjectProvider<DistributedCache> distributedCache,
            ObjectProvider<IdempotencyMetrics> metrics) {
        return DefaultIdempotencyService.builder(persistenceStore)
                .fingerprintStrategy(fingerprintStrategy)
                .serializer(serializer)
                .localCache(localCache.getIfAvailable())
                .distributedCache(distributedCache.getIfAvailable())
                .metrics(metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP))
                .persistenceTtl(properties.getPersistence().getTtl())
                .build();
    }
}
