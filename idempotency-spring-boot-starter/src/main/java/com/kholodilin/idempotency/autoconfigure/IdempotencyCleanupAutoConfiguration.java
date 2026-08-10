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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Conditionally registers persistence cleanup and a cron-scheduled job.
 *
 * <p>Scheduling is enabled only when cleanup is turned on, so applications that leave
 * the default {@code enabled=false} do not get a scheduler infrastructure for this
 * starter alone.
 */
@AutoConfiguration(after = IdempotencyJdbcAutoConfiguration.class)
@ConditionalOnClass(JdbcIdempotencyPersistenceCleanup.class)
@ConditionalOnBean({DataSource.class, PlatformTransactionManager.class})
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "idempotency.persistence.cleanup.enabled", havingValue = "true")
@EnableConfigurationProperties(IdempotencyProperties.class)
@EnableScheduling
public class IdempotencyCleanupAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IdempotencyPersistenceCleanup.class)
    public JdbcIdempotencyPersistenceCleanup jdbcIdempotencyPersistenceCleanup(
            DataSource dataSource, IdempotencyProperties properties) {
        return new JdbcIdempotencyPersistenceCleanup(
                dataSource, properties.getPersistence().getTableName());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencyPersistenceCleanupJob idempotencyPersistenceCleanupJob(
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
    public SchedulingConfigurer idempotencyPersistenceCleanupScheduling(
            IdempotencyPersistenceCleanupJob job, IdempotencyProperties properties) {
        String cron = properties.getPersistence().getCleanup().getCron();
        return taskRegistrar -> taskRegistrar.addCronTask(job::deleteExpired, cron);
    }
}
