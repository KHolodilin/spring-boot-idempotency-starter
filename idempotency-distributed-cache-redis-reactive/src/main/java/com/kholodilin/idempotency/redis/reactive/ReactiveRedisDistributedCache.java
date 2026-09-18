package com.kholodilin.idempotency.redis.reactive;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.reactive.spi.ReactiveDistributedCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ReactiveDistributedCache} backed by Redis.
 *
 * <p>Records are stored as JSON strings under {@code <prefix><operation>:<key>} with the
 * configured TTL. With the default {@link RedisCacheFailurePolicy#FAIL_OPEN} policy any
 * Redis failure is logged and treated as a cache miss — persistence remains the source
 * of truth and the business operation is never affected.
 */
@Slf4j
public final class ReactiveRedisDistributedCache implements ReactiveDistributedCache {

    public static final String DEFAULT_KEY_PREFIX = "idempotency:";
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ReactiveStringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration ttl;
    private final RedisCacheFailurePolicy failurePolicy;

    public ReactiveRedisDistributedCache(ReactiveRedisConnectionFactory connectionFactory) {
        this(connectionFactory, DEFAULT_KEY_PREFIX, DEFAULT_TTL, RedisCacheFailurePolicy.FAIL_OPEN);
    }

    public ReactiveRedisDistributedCache(
            ReactiveRedisConnectionFactory connectionFactory,
            String keyPrefix,
            Duration ttl,
            RedisCacheFailurePolicy failurePolicy) {
        Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.redis = new ReactiveStringRedisTemplate(connectionFactory);
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
    }

    @Override
    public Mono<Optional<IdempotencyRecord>> get(IdempotencyKey key) {
        return guarded(
                "get",
                key,
                redis.opsForValue()
                        .get(redisKey(key))
                        .map(json -> Optional.of(MAPPER.readValue(json, IdempotencyRecord.class)))
                        .defaultIfEmpty(Optional.empty()),
                Optional.empty());
    }

    @Override
    public Mono<Void> put(IdempotencyKey key, IdempotencyRecord record) {
        return guarded(
                        "put",
                        key,
                        Mono.fromCallable(() -> MAPPER.writeValueAsString(record))
                                .flatMap(json -> redis.opsForValue().set(redisKey(key), json, ttl))
                                .then(),
                        null)
                .then();
    }

    @Override
    public Mono<Void> evict(IdempotencyKey key) {
        return guarded("evict", key, redis.delete(redisKey(key)).then(), null).then();
    }

    String redisKey(IdempotencyKey key) {
        return keyPrefix + key.operation() + ":" + key.key();
    }

    private <T> Mono<T> guarded(String operation, IdempotencyKey key, Mono<T> body, T fallback) {
        return body.onErrorResume(RuntimeException.class, resume(operation, key, fallback));
    }

    private <T> Function<RuntimeException, Mono<T>> resume(String operation, IdempotencyKey key, T fallback) {
        return error -> {
            if (failurePolicy == RedisCacheFailurePolicy.FAIL_FAST) {
                return Mono.error(error);
            }
            log.warn(
                    "Redis idempotency cache {} failed for {}; continuing without distributed cache (fail-open)",
                    operation,
                    key,
                    error);
            return fallback == null ? Mono.empty() : Mono.just(fallback);
        };
    }
}
