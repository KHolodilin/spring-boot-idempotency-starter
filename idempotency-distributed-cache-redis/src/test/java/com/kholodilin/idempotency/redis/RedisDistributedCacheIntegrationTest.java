package com.kholodilin.idempotency.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.kholodilin.idempotency.IdempotencyKey;
import com.kholodilin.idempotency.IdempotencyRecord;
import com.kholodilin.idempotency.IdempotencyStatus;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class RedisDistributedCacheIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static LettuceConnectionFactory deadFactory;
    static StringRedisTemplate rawTemplate;

    static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @BeforeAll
    static void init() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2))
                        .build());
        factory.afterPropertiesSet();
        rawTemplate = new StringRedisTemplate(factory);

        deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(300))
                        .clientOptions(ClientOptions.builder()
                                .socketOptions(SocketOptions.builder()
                                        .connectTimeout(Duration.ofMillis(300))
                                        .build())
                                .build())
                        .build());
        deadFactory.afterPropertiesSet();
    }

    @AfterAll
    static void shutdown() {
        factory.destroy();
        deadFactory.destroy();
    }

    private static IdempotencyRecord completedRecord(String key) {
        return IdempotencyRecord.processing(
                        new IdempotencyKey("CREATE_PAYMENT", key), "hash-1", NOW, NOW.plusSeconds(3600))
                .completed("com.example.PaymentResult", "{\"paymentId\":\"pay-42\"}", NOW.plusSeconds(1));
    }

    @Test
    void putGetEvictRoundTripPreservesAllFields() {
        RedisDistributedCache cache = new RedisDistributedCache(factory);
        IdempotencyRecord record = completedRecord("rt-1");
        IdempotencyKey key = record.key();

        assertThat(cache.get(key)).isEmpty();

        cache.put(key, record);
        Optional<IdempotencyRecord> restored = cache.get(key);
        assertThat(restored).contains(record);

        cache.evict(key);
        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void rejectedRecordRoundTrips() {
        RedisDistributedCache cache = new RedisDistributedCache(factory);
        IdempotencyRecord rejected = IdempotencyRecord.processing(
                        new IdempotencyKey("CREATE_PAYMENT", "rt-2"), "hash-2", NOW, null)
                .rejected("INSUFFICIENT_FUNDS", "{\"amount\":1500,\"balance\":200}", NOW.plusSeconds(1));

        cache.put(rejected.key(), rejected);

        IdempotencyRecord restored = cache.get(rejected.key()).orElseThrow();
        assertThat(restored.status()).isEqualTo(IdempotencyStatus.REJECTED);
        assertThat(restored.errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(restored).isEqualTo(rejected);
    }

    @Test
    void keysUseConfiguredPrefixAndOperation() {
        RedisDistributedCache cache = new RedisDistributedCache(
                factory, "custom-prefix:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_OPEN);
        IdempotencyRecord record = completedRecord("key-fmt");

        cache.put(record.key(), record);

        assertThat(rawTemplate.hasKey("custom-prefix:CREATE_PAYMENT:key-fmt")).isTrue();
    }

    @Test
    void ttlIsAppliedToStoredEntries() {
        RedisDistributedCache cache = new RedisDistributedCache(
                factory, "ttl-test:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_OPEN);
        IdempotencyRecord record = completedRecord("ttl-1");

        cache.put(record.key(), record);

        Long expireSeconds = rawTemplate.getExpire("ttl-test:CREATE_PAYMENT:ttl-1");
        assertThat(expireSeconds).isBetween(1L, 300L);
    }

    @Test
    void failOpenSwallowsRedisFailures() {
        RedisDistributedCache cache =
                new RedisDistributedCache(deadFactory, "x:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_OPEN);
        IdempotencyRecord record = completedRecord("dead-1");

        assertThatCode(() -> {
                    assertThat(cache.get(record.key()))
                            .as("failure must look like a cache miss")
                            .isEmpty();
                    cache.put(record.key(), record);
                    cache.evict(record.key());
                })
                .doesNotThrowAnyException();
    }

    @Test
    void failFastPropagatesRedisFailures() {
        RedisDistributedCache cache =
                new RedisDistributedCache(deadFactory, "x:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_FAST);

        assertThatThrownBy(() -> cache.get(new IdempotencyKey("CREATE_PAYMENT", "dead-2")))
                .isInstanceOf(RedisConnectionFailureException.class);
    }
}
