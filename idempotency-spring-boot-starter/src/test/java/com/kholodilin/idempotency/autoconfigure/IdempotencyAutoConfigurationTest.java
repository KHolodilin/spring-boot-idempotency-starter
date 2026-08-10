package com.kholodilin.idempotency.autoconfigure;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.caffeine.CaffeineLocalCache;
import com.kholodilin.idempotency.jdbc.JdbcPersistenceStore;
import com.kholodilin.idempotency.jdbc.SchemaMode;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.redis.RedisDistributedCache;
import com.kholodilin.idempotency.spi.DistributedCache;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencyMetrics;
import com.kholodilin.idempotency.spi.IdempotencyPersistenceCleanup;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import com.kholodilin.idempotency.spi.LocalCache;
import com.kholodilin.idempotency.spi.PersistenceStore;
import com.kholodilin.idempotency.spi.TransactionContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IdempotencyAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    IdempotencyJdbcAutoConfiguration.class,
                    IdempotencyCaffeineAutoConfiguration.class,
                    IdempotencyRedisAutoConfiguration.class,
                    IdempotencyMetricsAutoConfiguration.class,
                    IdempotencyCleanupAutoConfiguration.class,
                    IdempotencyAutoConfiguration.class));

    private ApplicationContextRunner withDataSource() {
        return runner.withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("idempotency.persistence.schema.mode=none");
    }

    @Test
    void backsOffCompletelyWithoutDataSource() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(PersistenceStore.class);
            assertThat(context).doesNotHaveBean(IdempotencyService.class);
        });
    }

    @Test
    void createsServiceAndJdbcStoreWithDataSource() {
        withDataSource().run(context -> {
            assertThat(context).hasSingleBean(IdempotencyService.class);
            assertThat(context).hasSingleBean(FingerprintStrategy.class);
            assertThat(context).hasSingleBean(IdempotencySerializer.class);
            assertThat(context.getBean(PersistenceStore.class)).isInstanceOf(JdbcPersistenceStore.class);
            assertThat(context.getBean(TransactionContext.class)).isInstanceOf(SpringTransactionContext.class);
        });
    }

    @Test
    void caffeineLocalCacheIsAutoConfiguredFromClasspath() {
        withDataSource()
                .run(context -> assertThat(context.getBean(LocalCache.class)).isInstanceOf(CaffeineLocalCache.class));
    }

    @Test
    void localCacheCanBeDisabledByProperty() {
        withDataSource()
                .withPropertyValues("idempotency.local-cache.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LocalCache.class));
    }

    @Test
    void redisCacheRequiresConnectionFactory() {
        withDataSource().run(context -> assertThat(context).doesNotHaveBean(DistributedCache.class));
    }

    /**
     * Regression test: our Redis auto-configuration must be ordered after Boot's
     * {@code DataRedisAutoConfiguration}, otherwise {@code @ConditionalOnBean(RedisConnectionFactory)}
     * silently backs off and no distributed cache is created.
     */
    @Test
    void redisCacheIsCreatedAfterBootDataRedisAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class,
                        IdempotencyJdbcAutoConfiguration.class,
                        IdempotencyRedisAutoConfiguration.class,
                        IdempotencyAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues("idempotency.persistence.schema.mode=none")
                .run(context ->
                        assertThat(context.getBean(DistributedCache.class)).isInstanceOf(RedisDistributedCache.class));
    }

    @Test
    void redisCacheIsCreatedWithConnectionFactory() {
        withDataSource()
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .run(context ->
                        assertThat(context.getBean(DistributedCache.class)).isInstanceOf(RedisDistributedCache.class));
    }

    @Test
    void distributedCacheCanBeDisabledByProperty() {
        withDataSource()
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .withPropertyValues("idempotency.distributed-cache.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DistributedCache.class));
    }

    @Test
    void disabledPersistenceBacksOffServiceCreation() {
        withDataSource()
                .withPropertyValues("idempotency.persistence.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(PersistenceStore.class);
                    assertThat(context).doesNotHaveBean(IdempotencyService.class);
                });
    }

    @Test
    void masterSwitchDisablesEverything() {
        withDataSource().withPropertyValues("idempotency.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyService.class);
            assertThat(context).doesNotHaveBean(PersistenceStore.class);
            assertThat(context).doesNotHaveBean(LocalCache.class);
        });
    }

    @Test
    void metricsAreMicrometerBasedWhenMeterRegistryExists() {
        withDataSource().withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> assertThat(
                        context.getBean(IdempotencyMetrics.class))
                .isInstanceOf(MicrometerIdempotencyMetrics.class));
    }

    @Test
    void customBeansTakePrecedenceOverDefaults() {
        withDataSource().withUserConfiguration(CustomBeansConfiguration.class).run(context -> {
            assertThat(context.getBean(FingerprintStrategy.class))
                    .isSameAs(context.getBean(CustomBeansConfiguration.class).fingerprint);
            assertThat(context.getBean(PersistenceStore.class))
                    .isSameAs(context.getBean(CustomBeansConfiguration.class).store);
            assertThat(context).doesNotHaveBean(JdbcPersistenceStore.class);
            assertThat(context).hasSingleBean(IdempotencyService.class);
        });
    }

    @Test
    void propertiesAreBound() {
        withDataSource()
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
        withDataSource()
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IdempotencyPersistenceCleanup.class);
                    assertThat(context).doesNotHaveBean(IdempotencyPersistenceCleanupJob.class);
                    assertThat(context.getBean(IdempotencyProperties.class)
                                    .getPersistence()
                                    .getTtl())
                            .isEqualTo(Duration.ofDays(365));
                });
    }

    @Test
    void cleanupJobIsCreatedWhenEnabled() {
        withDataSource()
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withPropertyValues("idempotency.persistence.cleanup.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(IdempotencyPersistenceCleanup.class);
                    assertThat(context).hasSingleBean(IdempotencyPersistenceCleanupJob.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBeansConfiguration {

        final FingerprintStrategy fingerprint = request -> "constant";

        final PersistenceStore store = new PersistenceStore() {
            @Override
            public Optional<IdempotencyRecord> find(IdempotencyKey key) {
                return Optional.empty();
            }

            @Override
            public boolean acquire(IdempotencyKey key, String requestHash, Instant createdAt, Instant expiresAt) {
                return true;
            }

            @Override
            public void complete(IdempotencyKey key, String resultType, String resultPayload, Instant completedAt) {}

            @Override
            public void reject(IdempotencyKey key, String errorCode, String detailsPayload, Instant completedAt) {}
        };

        @Bean
        FingerprintStrategy customFingerprint() {
            return fingerprint;
        }

        @Bean
        PersistenceStore customStore() {
            return store;
        }
    }
}
