package com.kholodilin.idempotency.autoconfigure;

import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyServiceBuilder;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.idempotency.spi.TransactionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Core auto-configuration for the {@link IdempotencyService}. Default
 * {@link FingerprintStrategy} / {@link IdempotencySerializer} live in
 * {@link IdempotencyJacksonAutoConfiguration}.
 */
@AutoConfiguration(
        after = {
            IdempotencyJacksonAutoConfiguration.class,
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
    public TransactionContext idempotencyTransactionContext() {
        return new SpringTransactionContext();
    }

    @Bean
    @ConditionalOnMissingBean(IdempotencyService.class)
    @ConditionalOnBean(PersistenceStore.class)
    public IdempotencyService idempotencyService(
            IdempotencyProperties properties,
            PersistenceStore persistenceStore,
            FingerprintStrategy fingerprintStrategy,
            IdempotencySerializer serializer,
            TransactionContext transactionContext,
            ObjectProvider<LocalCache> localCache,
            ObjectProvider<DistributedCache> distributedCache,
            ObjectProvider<IdempotencyMetrics> metrics) {
        return new DefaultIdempotencyServiceBuilder(persistenceStore)
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
