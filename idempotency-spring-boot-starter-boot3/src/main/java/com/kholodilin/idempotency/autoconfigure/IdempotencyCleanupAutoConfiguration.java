package com.kholodilin.idempotency.autoconfigure;

import java.time.Clock;
import javax.sql.DataSource;

import com.kholodilin.idempotency.jdbc.JdbcIdempotencyPersistenceCleanup;
import com.kholodilin.idempotency.spi.IdempotencyPersistenceCleanup;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Conditionally registers persistence cleanup and a cron-scheduled job.
 *
 * <p>Scheduling is enabled only when cleanup is turned on, so applications that leave
 * the default {@code enabled=false} do not get a scheduler infrastructure for this
 * starter alone.
 *
 * <p>Boot 3.2's {@link ConditionalOnProperty} is not {@code @Repeatable}, so the cleanup
 * flag lives on a nested configuration rather than a second class-level annotation.
 */
@AutoConfiguration(after = IdempotencyJdbcAutoConfiguration.class)
@ConditionalOnClass(JdbcIdempotencyPersistenceCleanup.class)
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyCleanupAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "idempotency.persistence.cleanup.enabled", havingValue = "true")
    @EnableScheduling
    static class CleanupEnabledConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdempotencyPersistenceCleanup.class)
        JdbcIdempotencyPersistenceCleanup jdbcIdempotencyPersistenceCleanup(
                DataSource dataSource, IdempotencyProperties properties) {
            return new JdbcIdempotencyPersistenceCleanup(
                    dataSource, properties.getPersistence().getTableName());
        }

        @Bean
        @ConditionalOnMissingBean
        IdempotencyPersistenceCleanupJob idempotencyPersistenceCleanupJob(
                IdempotencyPersistenceCleanup cleanup,
                PlatformTransactionManager transactionManager,
                IdempotencyProperties properties) {
            return new IdempotencyPersistenceCleanupJob(
                    cleanup,
                    transactionManager,
                    Clock.systemUTC(),
                    properties.getPersistence().getCleanup().getBatchSize());
        }

        @Bean
        SchedulingConfigurer idempotencyPersistenceCleanupScheduling(
                IdempotencyPersistenceCleanupJob job, IdempotencyProperties properties) {
            String cron = properties.getPersistence().getCleanup().getCron();
            return taskRegistrar -> taskRegistrar.addCronTask(job::deleteExpired, cron);
        }
    }
}
