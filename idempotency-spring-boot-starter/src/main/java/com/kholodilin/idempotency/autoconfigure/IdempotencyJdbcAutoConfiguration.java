package com.kholodilin.idempotency.autoconfigure;

import javax.sql.DataSource;

import com.kholodilin.idempotency.jdbc.JdbcPersistenceStore;
import com.kholodilin.idempotency.jdbc.JdbcSchemaManager;
import com.kholodilin.idempotency.spi.PersistenceStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the JDBC {@link PersistenceStore} and schema management when a
 * {@link DataSource} is available.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
@ConditionalOnClass(JdbcPersistenceStore.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyJdbcAutoConfiguration {

    @Bean(initMethod = "initialize")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "idempotency.persistence.enabled", matchIfMissing = true)
    public JdbcSchemaManager idempotencySchemaManager(DataSource dataSource, IdempotencyProperties properties) {
        return new JdbcSchemaManager(
                dataSource,
                properties.getPersistence().getTableName(),
                properties.getPersistence().getSchema().getMode());
    }

    @Bean
    @ConditionalOnMissingBean(PersistenceStore.class)
    @ConditionalOnProperty(name = "idempotency.persistence.enabled", matchIfMissing = true)
    public JdbcPersistenceStore jdbcPersistenceStore(
            DataSource dataSource,
            IdempotencyProperties properties,
            // enforces schema initialization before the store is usable
            JdbcSchemaManager idempotencySchemaManager) {
        return new JdbcPersistenceStore(
                dataSource, properties.getPersistence().getTableName(), java.time.Clock.systemUTC());
    }
}
