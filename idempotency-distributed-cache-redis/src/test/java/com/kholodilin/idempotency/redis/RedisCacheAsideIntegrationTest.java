package com.kholodilin.idempotency.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.caffeine.CaffeineLocalCache;
import com.kholodilin.idempotency.core.DefaultIdempotencyService;
import com.kholodilin.idempotency.core.DefaultIdempotencyServiceBuilder;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.testsupport.InMemoryStore;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache-aside behaviour of the whole L1 (Caffeine) → L2 (Redis) → persistence chain
 * with a real Redis instance.
 */
@Testcontainers
class RedisCacheAsideIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory factory;

    final InMemoryStore store = new InMemoryStore();
    final AtomicInteger actionCalls = new AtomicInteger();

    RedisDistributedCache redisCache;

    record PaymentResult(String paymentId) {}

    @BeforeAll
    static void initFactory() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofSeconds(2))
                        .build());
        factory.afterPropertiesSet();
    }

    @AfterAll
    static void shutdown() {
        factory.destroy();
    }

    @BeforeEach
    void initCache() {
        redisCache = new RedisDistributedCache(
                factory,
                "cache-aside-" + System.nanoTime() + ":",
                Duration.ofMinutes(5),
                RedisCacheFailurePolicy.FAIL_OPEN);
    }

    private DefaultIdempotencyService service(CaffeineLocalCache caffeine, RedisDistributedCache redis) {
        return new DefaultIdempotencyServiceBuilder(store)
                .localCache(caffeine)
                .distributedCache(redis)
                .requireActiveTransaction(false)
                .build();
    }

    private ExecutionResult<PaymentResult> action() {
        actionCalls.incrementAndGet();
        return ExecutionResult.success(new PaymentResult("pay-42"));
    }

    @Test
    void freshOutcomePopulatesRedisAndCaffeine() {
        CaffeineLocalCache caffeine = new CaffeineLocalCache();
        DefaultIdempotencyService service = service(caffeine, redisCache);

        service.operation("CREATE_PAYMENT").key("fresh-1").request("req").execute(PaymentResult.class, this::action);

        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "fresh-1");
        assertThat(redisCache.get(key)).map(IdempotencyRecord::status).contains(IdempotencyStatus.COMPLETED);
        assertThat(caffeine.get(key)).isPresent();
    }

    @Test
    void redisHitIsPromotedToCaffeineWithoutTouchingPersistence() {
        // first instance of the application populates Redis
        service(new CaffeineLocalCache(), redisCache)
                .operation("CREATE_PAYMENT")
                .key("promo-1")
                .request("req")
                .execute(PaymentResult.class, this::action);
        int findsAfterFirstExecution = store.findCalls.get();

        // second instance: empty Caffeine, shared Redis
        CaffeineLocalCache freshCaffeine = new CaffeineLocalCache();
        ExecutionResult<PaymentResult> replayed = service(freshCaffeine, redisCache)
                .operation("CREATE_PAYMENT")
                .key("promo-1")
                .request("req")
                .execute(PaymentResult.class, this::action);

        assertThat(actionCalls).hasValue(1);
        assertThat(replayed.isSuccess()).isTrue();
        assertThat(store.findCalls).as("replay must be served from Redis").hasValue(findsAfterFirstExecution);
        assertThat(freshCaffeine.get(new IdempotencyKey("CREATE_PAYMENT", "promo-1")))
                .as("L2 hit must be promoted to L1")
                .isPresent();
    }

    @Test
    void persistenceHitIsPromotedToRedisAndCaffeine() {
        // outcome exists only in persistence (e.g. caches were cold-restarted)
        DefaultIdempotencyService noCacheService = new DefaultIdempotencyServiceBuilder(store)
                .requireActiveTransaction(false)
                .build();
        noCacheService
                .operation("CREATE_PAYMENT")
                .key("cold-1")
                .request("req")
                .execute(PaymentResult.class, this::action);

        CaffeineLocalCache caffeine = new CaffeineLocalCache();
        ExecutionResult<PaymentResult> replayed = service(caffeine, redisCache)
                .operation("CREATE_PAYMENT")
                .key("cold-1")
                .request("req")
                .execute(PaymentResult.class, this::action);

        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "cold-1");
        assertThat(actionCalls).hasValue(1);
        assertThat(replayed.isSuccess()).isTrue();
        assertThat(redisCache.get(key))
                .as("persistence hit must be promoted to L2")
                .isPresent();
        assertThat(caffeine.get(key))
                .as("persistence hit must be promoted to L1")
                .isPresent();
    }

    @Test
    void unavailableRedisFallsBackToPersistence() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(300))
                        .clientOptions(ClientOptions.builder()
                                .socketOptions(SocketOptions.builder()
                                        .connectTimeout(Duration.ofMillis(300))
                                        .build())
                                .build())
                        .build());
        dead.afterPropertiesSet();
        try {
            RedisDistributedCache deadCache =
                    new RedisDistributedCache(dead, "dead:", Duration.ofMinutes(5), RedisCacheFailurePolicy.FAIL_OPEN);
            DefaultIdempotencyService service = service(new CaffeineLocalCache(), deadCache);

            ExecutionResult<PaymentResult> first = service.operation("CREATE_PAYMENT")
                    .key("dead-ca-1")
                    .request("req")
                    .execute(PaymentResult.class, this::action);
            ExecutionResult<PaymentResult> replayed = service(new CaffeineLocalCache(), deadCache)
                    .operation("CREATE_PAYMENT")
                    .key("dead-ca-1")
                    .request("req")
                    .execute(PaymentResult.class, this::action);

            assertThat(actionCalls)
                    .as("first executes, second replays from persistence")
                    .hasValue(1);
            assertThat(replayed).isEqualTo(first);
        } finally {
            dead.destroy();
        }
    }
}
