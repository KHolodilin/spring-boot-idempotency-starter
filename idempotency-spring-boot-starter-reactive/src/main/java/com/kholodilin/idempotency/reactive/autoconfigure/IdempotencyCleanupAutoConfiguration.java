package com.kholodilin.idempotency.reactive.autoconfigure;

import java.time.Clock;

import com.kholodilin.idempotency.r2dbc.R2dbcIdempotencyPersistenceCleanup;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Conditionally registers persistence cleanup and a cron-scheduled job.
 *
 * <p>Scheduling is enabled only when cleanup is turned on, so applications that leave
 * the default {@code enabled=false} do not get a scheduler infrastructure for this
 * starter alone.
 */
@AutoConfiguration(after = IdempotencyR2dbcAutoConfiguration.class)
@ConditionalOnClass(R2dbcIdempotencyPersistenceCleanup.class)
@ConditionalOnBean({ConnectionFactory.class, ReactiveTransactionManager.class})
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "idempotency.persistence.cleanup.enabled", havingValue = "true")
@EnableConfigurationProperties(IdempotencyProperties.class)
@EnableScheduling
public class IdempotencyCleanupAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public R2dbcIdempotencyPersistenceCleanup r2dbcIdempotencyPersistenceCleanup(
            DatabaseClient databaseClient, IdempotencyProperties properties) {
        return new R2dbcIdempotencyPersistenceCleanup(
                databaseClient, properties.getPersistence().getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    public ReactiveIdempotencyPersistenceCleanupJob idempotencyPersistenceCleanupJob(
            R2dbcIdempotencyPersistenceCleanup cleanup,
            ReactiveTransactionManager transactionManager,
            IdempotencyProperties properties) {
        return new ReactiveIdempotencyPersistenceCleanupJob(
                cleanup,
                TransactionalOperator.create(transactionManager),
                Clock.systemUTC(),
                properties.getPersistence().getCleanup().getBatchSize());
    }

    @Bean
    public SchedulingConfigurer idempotencyPersistenceCleanupScheduling(
            ReactiveIdempotencyPersistenceCleanupJob job, IdempotencyProperties properties) {
        String cron = properties.getPersistence().getCleanup().getCron();
        return taskRegistrar -> taskRegistrar.addCronTask(job::deleteExpired, cron);
    }
}
