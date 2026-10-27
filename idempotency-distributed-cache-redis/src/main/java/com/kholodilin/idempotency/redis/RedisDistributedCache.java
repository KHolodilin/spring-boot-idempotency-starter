package com.kholodilin.idempotency.redis;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link DistributedCache} backed by Redis.
 *
 * <p>Records are stored as JSON strings under {@code <prefix><operation>:<key>} with the
 * configured TTL. With the default {@link RedisCacheFailurePolicy#FAIL_OPEN} policy any
 * Redis failure is logged and treated as a cache miss — persistence remains the source
 * of truth and the business operation is never affected.
 */
@Slf4j
public final class RedisDistributedCache implements DistributedCache {

    public static final String DEFAULT_KEY_PREFIX = "idempotency:";
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttl;
    private final RedisCacheFailurePolicy failurePolicy;
    private final IdempotencySerializer serializer;

    public RedisDistributedCache(RedisConnectionFactory connectionFactory, IdempotencySerializer serializer) {
        this(connectionFactory, DEFAULT_KEY_PREFIX, DEFAULT_TTL, RedisCacheFailurePolicy.FAIL_OPEN, serializer);
    }

    public RedisDistributedCache(
            RedisConnectionFactory connectionFactory,
            String keyPrefix,
            Duration ttl,
            RedisCacheFailurePolicy failurePolicy,
            IdempotencySerializer serializer) {
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.redis = new StringRedisTemplate(connectionFactory);
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
    }

    @Override
    public Optional<IdempotencyRecord> get(IdempotencyKey key) {
        return guarded(
                "get",
                key,
                () -> {
                    String json = redis.opsForValue().get(redisKey(key));
                    if (json == null) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(serializer.deserialize(json, IdempotencyRecord.class));
                },
                Optional.empty());
    }

    @Override
    public void put(IdempotencyKey key, IdempotencyRecord record) {
        guarded(
                "put",
                key,
                () -> {
                    redis.opsForValue().set(redisKey(key), serializer.serialize(record), ttl);
                    return null;
                },
                null);
    }

    @Override
    public void evict(IdempotencyKey key) {
        guarded(
                "evict",
                key,
                () -> {
                    redis.delete(redisKey(key));
                    return null;
                },
                null);
    }

    String redisKey(IdempotencyKey key) {
        return keyPrefix + key.operation() + ":" + key.key();
    }

    private <T> T guarded(String operation, IdempotencyKey key, Supplier<T> body, @Nullable T fallback) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            if (failurePolicy == RedisCacheFailurePolicy.FAIL_FAST) {
                throw e;
            }
            log.warn(
                    "Redis idempotency cache {} failed for {}; continuing without distributed cache (fail-open)",
                    operation,
                    key,
                    e);
            return fallback;
        }
    }
}
