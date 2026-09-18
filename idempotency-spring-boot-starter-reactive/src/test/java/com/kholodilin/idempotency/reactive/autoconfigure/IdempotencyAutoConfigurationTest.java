package com.kholodilin.idempotency.reactive.autoconfigure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.kholodilin.idempotency.caffeine.CaffeineLocalCache;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.r2dbc.R2dbcIdempotencyPersistenceCleanup;
import com.kholodilin.idempotency.r2dbc.R2dbcPersistenceStore;
import com.kholodilin.idempotency.r2dbc.SchemaMode;
import com.kholodilin.idempotency.reactive.ReactiveIdempotencyService;
import com.kholodilin.idempotency.reactive.spi.ReactiveDistributedCache;
import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import com.kholodilin.idempotency.reactive.spi.ReactiveTransactionContext;
import com.kholodilin.idempotency.redis.reactive.ReactiveRedisDistributedCache;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IdempotencyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    IdempotencyR2dbcAutoConfiguration.class,
                    IdempotencyCaffeineAutoConfiguration.class,
                    IdempotencyRedisAutoConfiguration.class,
                    IdempotencyMetricsAutoConfiguration.class,
                    IdempotencyCleanupAutoConfiguration.class,
                    IdempotencyAutoConfiguration.class));

    private ApplicationContextRunner withConnectionFactory() {
        return runner.withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withBean(DatabaseClient.class, () -> mock(DatabaseClient.class))
                .withPropertyValues("idempotency.persistence.schema.mode=none");
    }

    @Test
    void backsOffCompletelyWithoutConnectionFactory() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ReactivePersistenceStore.class);
            assertThat(context).doesNotHaveBean(ReactiveIdempotencyService.class);
        });
    }

    @Test
    void backsOffWithoutDatabaseClient() {
        runner.withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
                .withPropertyValues("idempotency.persistence.schema.mode=none")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ReactivePersistenceStore.class);
                    assertThat(context).doesNotHaveBean(ReactiveIdempotencyService.class);
                });
    }

    @Test
    void createsServiceAndR2dbcStoreWithConnectionFactory() {
        withConnectionFactory().run(context -> {
            assertThat(context).hasSingleBean(ReactiveIdempotencyService.class);
            assertThat(context).hasSingleBean(FingerprintStrategy.class);
            assertThat(context).hasSingleBean(IdempotencySerializer.class);
            assertThat(context.getBean(ReactivePersistenceStore.class)).isInstanceOf(R2dbcPersistenceStore.class);
            assertThat(context.getBean(ReactiveTransactionContext.class))
                    .isInstanceOf(SpringReactiveTransactionContext.class);
        });
    }

    @Test
    void caffeineLocalCacheIsAutoConfiguredFromClasspath() {
        withConnectionFactory()
                .run(context -> assertThat(context.getBean(LocalCache.class)).isInstanceOf(CaffeineLocalCache.class));
    }

    @Test
    void localCacheCanBeDisabledByProperty() {
        withConnectionFactory()
                .withPropertyValues("idempotency.local-cache.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LocalCache.class));
    }

    @Test
    void redisCacheRequiresConnectionFactory() {
        withConnectionFactory().run(context -> assertThat(context).doesNotHaveBean(ReactiveDistributedCache.class));
    }

    @Test
    void redisCacheIsCreatedWithConnectionFactory() {
        withConnectionFactory()
                .withBean(ReactiveRedisConnectionFactory.class, () -> mock(ReactiveRedisConnectionFactory.class))
                .run(context -> assertThat(context.getBean(ReactiveDistributedCache.class))
                        .isInstanceOf(ReactiveRedisDistributedCache.class));
    }

    @Test
    void distributedCacheCanBeDisabledByProperty() {
        withConnectionFactory()
                .withBean(ReactiveRedisConnectionFactory.class, () -> mock(ReactiveRedisConnectionFactory.class))
                .withPropertyValues("idempotency.distributed-cache.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ReactiveDistributedCache.class));
    }

    @Test
    void disabledPersistenceBacksOffServiceCreation() {
        withConnectionFactory()
                .withPropertyValues("idempotency.persistence.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ReactivePersistenceStore.class);
                    assertThat(context).doesNotHaveBean(ReactiveIdempotencyService.class);
                });
    }

    @Test
    void masterSwitchDisablesEverything() {
        withConnectionFactory().withPropertyValues("idempotency.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ReactiveIdempotencyService.class);
            assertThat(context).doesNotHaveBean(ReactivePersistenceStore.class);
            assertThat(context).doesNotHaveBean(LocalCache.class);
        });
    }

    @Test
    void metricsAreMicrometerBasedWhenMeterRegistryExists() {
        withConnectionFactory()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context.getBean(IdempotencyMetrics.class))
                        .isInstanceOf(MicrometerIdempotencyMetrics.class));
    }

    @Test
    void customBeansTakePrecedenceOverDefaults() {
        withConnectionFactory()
                .withUserConfiguration(CustomBeansConfiguration.class)
                .run(context -> {
                    assertThat(context.getBean(FingerprintStrategy.class))
                            .isSameAs(context.getBean(CustomBeansConfiguration.class).fingerprint);
                    assertThat(context.getBean(ReactivePersistenceStore.class))
                            .isSameAs(context.getBean(CustomBeansConfiguration.class).store);
                    assertThat(context).doesNotHaveBean(R2dbcPersistenceStore.class);
                    assertThat(context).hasSingleBean(ReactiveIdempotencyService.class);
                });
    }

    @Test
    void propertiesAreBound() {
        withConnectionFactory()
                .withPropertyValues(
                        "idempotency.fingerprint.algorithm=SHA-512",
                        "idempotency.local-cache.ttl=5m",
                        "idempotency.local-cache.max-size=42",
                        "idempotency.distributed-cache.ttl=2h",
                        "idempotency.distributed-cache.key-prefix=custom:",
                        "idempotency.distributed-cache.failure-policy=fail-fast",
                        "idempotency.persistence.table-name=custom_records",
                        "idempotency.persistence.ttl=30d",
                        "idempotency.persistence.lookup-before-acquire=true",
                        "idempotency.persistence.cleanup.enabled=true",
                        "idempotency.persistence.cleanup.cron=0 15 4 * * *",
                        "idempotency.persistence.cleanup.batch-size=250")
                .run(context -> {
                    IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
                    assertThat(properties.getFingerprint().getAlgorithm()).isEqualTo("SHA-512");
                    assertThat(properties.getLocalCache().getTtl()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.getLocalCache().getMaxSize()).isEqualTo(42);
                    assertThat(properties.getDistributedCache().getTtl()).isEqualTo(Duration.ofHours(2));
                    assertThat(properties.getDistributedCache().getKeyPrefix()).isEqualTo("custom:");
                    assertThat(properties.getDistributedCache().getFailurePolicy())
                            .isEqualTo("fail-fast");
                    assertThat(properties.getPersistence().getTableName()).isEqualTo("custom_records");
                    assertThat(properties.getPersistence().getTtl()).isEqualTo(Duration.ofDays(30));
                    assertThat(properties.getPersistence().isLookupBeforeAcquire())
                            .isTrue();
                    assertThat(properties.getPersistence().getCleanup().isEnabled())
                            .isTrue();
                    assertThat(properties.getPersistence().getCleanup().getCron())
                            .isEqualTo("0 15 4 * * *");
                    assertThat(properties.getPersistence().getCleanup().getBatchSize())
                            .isEqualTo(250);
                    assertThat(properties.getPersistence().getSchema().getMode())
                            .isEqualTo(SchemaMode.NONE);
                });
    }

    @Test
    void cleanupIsDisabledByDefault() {
        withConnectionFactory()
                .withBean(ReactiveTransactionManager.class, () -> mock(ReactiveTransactionManager.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(R2dbcIdempotencyPersistenceCleanup.class);
                    assertThat(context).doesNotHaveBean(ReactiveIdempotencyPersistenceCleanupJob.class);
                    assertThat(context.getBean(IdempotencyProperties.class)
                                    .getPersistence()
                                    .getTtl())
                            .isEqualTo(Duration.ofDays(365));
                });
    }

    @Test
    void cleanupJobIsCreatedWhenEnabled() {
        withConnectionFactory()
                .withBean(ReactiveTransactionManager.class, () -> mock(ReactiveTransactionManager.class))
                .withPropertyValues("idempotency.persistence.cleanup.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(R2dbcIdempotencyPersistenceCleanup.class);
                    assertThat(context).hasSingleBean(ReactiveIdempotencyPersistenceCleanupJob.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBeansConfiguration {

        final FingerprintStrategy fingerprint = request -> "constant";

        final ReactivePersistenceStore store = new ReactivePersistenceStore() {
            @Override
            public Mono<Optional<IdempotencyRecord>> find(IdempotencyKey key) {
                return Mono.just(Optional.empty());
            }

            @Override
            public Mono<Boolean> acquire(IdempotencyKey key, String requestHash, Instant createdAt, Instant expiresAt) {
                return Mono.just(true);
            }

            @Override
            public Mono<Void> complete(
                    IdempotencyKey key, String resultType, String resultPayload, Instant completedAt) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> reject(IdempotencyKey key, String errorCode, String detailsPayload, Instant completedAt) {
                return Mono.empty();
            }
        };

        @Bean
        FingerprintStrategy customFingerprint() {
            return fingerprint;
        }

        @Bean
        ReactivePersistenceStore customStore() {
            return store;
        }
    }
}
