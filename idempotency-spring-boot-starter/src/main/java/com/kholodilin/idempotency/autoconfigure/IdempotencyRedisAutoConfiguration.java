package com.kholodilin.idempotency.autoconfigure;

import java.util.Locale;

import com.kholodilin.idempotency.redis.RedisCacheFailurePolicy;
import com.kholodilin.idempotency.redis.RedisDistributedCache;
import com.kholodilin.idempotency.spi.DistributedCache;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Auto-configures the Redis {@link DistributedCache} when the
 * {@code idempotency-distributed-cache-redis} module is on the classpath and a
 * {@link RedisConnectionFactory} bean is available.
 */
@AutoConfiguration(
        afterName = {
            // Spring Boot 4.x
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            // Spring Boot 3.x (legacy name, ignored when absent)
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        })
@ConditionalOnClass({RedisDistributedCache.class, RedisConnectionFactory.class})
@ConditionalOnBean(RedisConnectionFactory.class)
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedCache.class)
    @ConditionalOnProperty(name = "idempotency.distributed-cache.enabled", matchIfMissing = true)
    public RedisDistributedCache redisIdempotencyDistributedCache(
            RedisConnectionFactory connectionFactory, IdempotencyProperties properties) {
        IdempotencyProperties.DistributedCacheSettings config = properties.getDistributedCache();
        RedisCacheFailurePolicy policy = RedisCacheFailurePolicy.valueOf(
                config.getFailurePolicy().toUpperCase(Locale.ROOT).replace('-', '_'));
        return new RedisDistributedCache(connectionFactory, config.getKeyPrefix(), config.getTtl(), policy);
    }
}
