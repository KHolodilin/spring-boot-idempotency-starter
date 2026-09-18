package com.kholodilin.idempotency.reactive.autoconfigure;

import com.kholodilin.idempotency.r2dbc.R2dbcPersistenceStore;
import com.kholodilin.idempotency.r2dbc.R2dbcSchemaManager;
import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Auto-configures the R2DBC {@link ReactivePersistenceStore} and schema management when a
 * {@link ConnectionFactory} is available.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration",
            "org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration"
        })
@ConditionalOnClass(R2dbcPersistenceStore.class)
@ConditionalOnBean({ConnectionFactory.class, DatabaseClient.class})
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyR2dbcAutoConfiguration {

    @Bean(initMethod = "initializeBlocking")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "idempotency.persistence.enabled", matchIfMissing = true)
    public R2dbcSchemaManager idempotencySchemaManager(
            DatabaseClient databaseClient, IdempotencyProperties properties) {
        return new R2dbcSchemaManager(
                databaseClient,
                properties.getPersistence().getTableName(),
                properties.getPersistence().getSchema().getMode());
    }

    @Bean
    @ConditionalOnMissingBean(ReactivePersistenceStore.class)
    @ConditionalOnProperty(name = "idempotency.persistence.enabled", matchIfMissing = true)
    public R2dbcPersistenceStore r2dbcPersistenceStore(
            DatabaseClient databaseClient,
            IdempotencyProperties properties,
            R2dbcSchemaManager idempotencySchemaManager) {
        return new R2dbcPersistenceStore(
                databaseClient, properties.getPersistence().getTableName());
    }
}
