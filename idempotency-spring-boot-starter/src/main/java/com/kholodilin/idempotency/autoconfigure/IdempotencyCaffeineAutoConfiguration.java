package com.kholodilin.idempotency.autoconfigure;

import com.kholodilin.idempotency.caffeine.CaffeineLocalCache;
import com.kholodilin.idempotency.spi.LocalCache;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the Caffeine {@link LocalCache} when the
 * {@code idempotency-local-cache-caffeine} module is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(CaffeineLocalCache.class)
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyCaffeineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(LocalCache.class)
    @ConditionalOnProperty(name = "idempotency.local-cache.enabled", matchIfMissing = true)
    public CaffeineLocalCache caffeineIdempotencyLocalCache(IdempotencyProperties properties) {
        IdempotencyProperties.LocalCacheSettings config = properties.getLocalCache();
        return new CaffeineLocalCache(config.getTtl(), config.getMaxSize(), config.isStatistics());
    }
}
