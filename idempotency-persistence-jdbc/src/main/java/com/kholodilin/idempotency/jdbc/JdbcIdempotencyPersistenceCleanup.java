package com.kholodilin.idempotency.jdbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;

import com.kholodilin.idempotency.spi.IdempotencyPersistenceCleanup;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Physically deletes expired rows from the JDBC/PostgreSQL idempotency table.
 *
 * <p>Uses {@code FOR UPDATE SKIP LOCKED} so cleanup does not wait on rows locked by
 * in-flight request transactions.
 */
public final class JdbcIdempotencyPersistenceCleanup implements IdempotencyPersistenceCleanup {

    private final JdbcClient jdbc;
    private final String deleteSql;

    public JdbcIdempotencyPersistenceCleanup(DataSource dataSource) {
        this(dataSource, JdbcPersistenceStore.DEFAULT_TABLE_NAME);
    }

    public JdbcIdempotencyPersistenceCleanup(DataSource dataSource, String tableName) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        String table = JdbcPersistenceStore.validateTableName(tableName);
        this.deleteSql = """
                DELETE FROM %s \
                WHERE ctid IN ( \
                  SELECT ctid FROM %s \
                  WHERE expires_at IS NOT NULL AND expires_at < ? \
                  ORDER BY expires_at \
                  FOR UPDATE SKIP LOCKED \
                  LIMIT ? \
                )""".formatted(table, table);
    }

    @Override
    public int deleteExpired(Instant before, int limit) {
        Objects.requireNonNull(before, "before");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return jdbc.sql(deleteSql)
                .param(1, OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .param(2, limit)
                .update();
    }
}
