package com.kholodilin.idempotency.redis.reactive;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ReactiveRedisDistributedCacheIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static LettuceConnectionFactory deadFactory;
    static ReactiveStringRedisTemplate rawTemplate;

    static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @BeforeAll
    static void init() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2))
                        .build());
        factory.afterPropertiesSet();
        factory.start();
        rawTemplate = new ReactiveStringRedisTemplate(factory);

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
        deadFactory.start();
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
        ReactiveRedisDistributedCache cache = new ReactiveRedisDistributedCache(factory);
        IdempotencyRecord record = completedRecord("rt-1");
        IdempotencyKey key = record.key();

        StepVerifier.create(cache.get(key)).expectNext(Optional.empty()).verifyComplete();

        StepVerifier.create(cache.put(key, record).then(cache.get(key)))
                .assertNext(restored -> assertThat(restored).contains(record))
                .verifyComplete();

        StepVerifier.create(cache.evict(key).then(cache.get(key)))
                .expectNext(Optional.empty())
                .verifyComplete();
    }

    @Test
    void rejectedRecordRoundTrips() {
        ReactiveRedisDistributedCache cache = new ReactiveRedisDistributedCache(factory);
        IdempotencyRecord rejected = IdempotencyRecord.processing(
                        new IdempotencyKey("CREATE_PAYMENT", "rt-2"), "hash-2", NOW, null)
                .rejected("INSUFFICIENT_FUNDS", "{\"amount\":1500,\"balance\":200}", NOW.plusSeconds(1));

        StepVerifier.create(cache.put(rejected.key(), rejected).then(cache.get(rejected.key())))
                .assertNext(restored -> {
                    assertThat(restored).isPresent();
                    assertThat(restored.get().status()).isEqualTo(IdempotencyStatus.REJECTED);
                    assertThat(restored.get().errorCode()).isEqualTo("INSUFFICIENT_FUNDS");
                    assertThat(restored.get()).isEqualTo(rejected);
                })
                .verifyComplete();
    }

    @Test
    void keysUseConfiguredPrefixAndOperation() {
        ReactiveRedisDistributedCache cache = new ReactiveRedisDistributedCache(
                factory, "custom-prefix:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_OPEN);
        IdempotencyRecord record = completedRecord("key-fmt");

        StepVerifier.create(cache.put(record.key(), record)
                        .then(rawTemplate.hasKey("custom-prefix:CREATE_PAYMENT:key-fmt")))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void ttlIsAppliedToStoredEntries() {
        ReactiveRedisDistributedCache cache = new ReactiveRedisDistributedCache(
                factory, "ttl-test:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_OPEN);
        IdempotencyRecord record = completedRecord("ttl-1");

        StepVerifier.create(
                        cache.put(record.key(), record).then(rawTemplate.getExpire("ttl-test:CREATE_PAYMENT:ttl-1")))
                .assertNext(expire -> assertThat(expire).isBetween(Duration.ofSeconds(1), Duration.ofMinutes(5)))
                .verifyComplete();
    }

    @Test
    void failOpenSwallowsRedisFailures() {
        ReactiveRedisDistributedCache cache = new ReactiveRedisDistributedCache(
                deadFactory, "x:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_OPEN);
        IdempotencyRecord record = completedRecord("dead-1");

        StepVerifier.create(cache.get(record.key())
                        .doOnNext(hit -> assertThat(hit)
                                .as("failure must look like a cache miss")
                                .isEmpty())
                        .then(cache.put(record.key(), record))
                        .then(cache.evict(record.key())))
                .verifyComplete();
    }

    @Test
    void failFastPropagatesRedisFailures() {
        ReactiveRedisDistributedCache cache = new ReactiveRedisDistributedCache(
                deadFactory, "x:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_FAST);

        StepVerifier.create(cache.get(new IdempotencyKey("CREATE_PAYMENT", "dead-2")))
                .expectError(RuntimeException.class)
                .verify();
    }
}
