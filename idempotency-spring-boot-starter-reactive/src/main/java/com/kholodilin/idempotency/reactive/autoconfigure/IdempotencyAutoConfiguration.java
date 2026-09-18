package com.kholodilin.idempotency.reactive.autoconfigure;

import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson.JacksonIdempotencySerializer;
import com.kholodilin.idempotency.reactive.ReactiveIdempotencyService;
import com.kholodilin.idempotency.reactive.core.DefaultReactiveIdempotencyServiceBuilder;
import com.kholodilin.idempotency.reactive.spi.ReactiveDistributedCache;
import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import com.kholodilin.idempotency.reactive.spi.ReactiveTransactionContext;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Core auto-configuration: default {@link FingerprintStrategy}, {@link IdempotencySerializer}
 * and the {@link ReactiveIdempotencyService} itself. Every default backs off when the
 * application provides its own bean of the same SPI type.
 */
@AutoConfiguration(
        after = {
            IdempotencyR2dbcAutoConfiguration.class,
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
    @ConditionalOnMissingBean
    public ReactiveTransactionContext idempotencyReactiveTransactionContext() {
        return new SpringReactiveTransactionContext();
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveIdempotencyService.class)
    @ConditionalOnBean(ReactivePersistenceStore.class)
    public ReactiveIdempotencyService reactiveIdempotencyService(
            IdempotencyProperties properties,
            ReactivePersistenceStore persistenceStore,
            FingerprintStrategy fingerprintStrategy,
            IdempotencySerializer serializer,
            ReactiveTransactionContext transactionContext,
            ObjectProvider<LocalCache> localCache,
            ObjectProvider<ReactiveDistributedCache> distributedCache,
            ObjectProvider<IdempotencyMetrics> metrics) {
        return new DefaultReactiveIdempotencyServiceBuilder(persistenceStore)
                .fingerprintStrategy(fingerprintStrategy)
                .serializer(serializer)
                .transactionContext(transactionContext)
                .localCache(localCache.getIfAvailable())
                .distributedCache(distributedCache.getIfAvailable())
                .metrics(metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP))
                .persistenceTtl(properties.getPersistence().getTtl())
                .lookupBeforeAcquire(properties.getPersistence().isLookupBeforeAcquire())
                .build();
    }
}
