package com.kholodilin.idempotency.reactive.autoconfigure;

import java.util.Locale;

import com.kholodilin.idempotency.reactive.spi.ReactiveDistributedCache;
import com.kholodilin.idempotency.redis.reactive.ReactiveRedisDistributedCache;
import com.kholodilin.idempotency.redis.reactive.RedisCacheFailurePolicy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;

/**
 * Auto-configures the Redis {@link ReactiveDistributedCache} when the
 * {@code idempotency-distributed-cache-redis-reactive} module is on the classpath and a
 * {@link ReactiveRedisConnectionFactory} bean is available.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration",
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
        })
@ConditionalOnClass({ReactiveRedisDistributedCache.class, ReactiveRedisConnectionFactory.class})
@ConditionalOnBean(ReactiveRedisConnectionFactory.class)
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ReactiveDistributedCache.class)
    @ConditionalOnProperty(name = "idempotency.distributed-cache.enabled", matchIfMissing = true)
    public ReactiveRedisDistributedCache redisIdempotencyDistributedCache(
            ReactiveRedisConnectionFactory connectionFactory, IdempotencyProperties properties) {
        IdempotencyProperties.DistributedCacheSettings config = properties.getDistributedCache();
        RedisCacheFailurePolicy policy = RedisCacheFailurePolicy.valueOf(
                config.getFailurePolicy().toUpperCase(Locale.ROOT).replace('-', '_'));
        return new ReactiveRedisDistributedCache(connectionFactory, config.getKeyPrefix(), config.getTtl(), policy);
    }
}
