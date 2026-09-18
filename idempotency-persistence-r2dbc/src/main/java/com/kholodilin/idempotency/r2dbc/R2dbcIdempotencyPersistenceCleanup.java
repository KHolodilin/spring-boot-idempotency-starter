package com.kholodilin.idempotency.r2dbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * Physically deletes expired rows from the R2DBC/PostgreSQL idempotency table.
 */
public final class R2dbcIdempotencyPersistenceCleanup {

    private final DatabaseClient databaseClient;
    private final String deleteSql;

    public R2dbcIdempotencyPersistenceCleanup(DatabaseClient databaseClient) {
        this(databaseClient, R2dbcPersistenceStore.DEFAULT_TABLE_NAME);
    }

    public R2dbcIdempotencyPersistenceCleanup(DatabaseClient databaseClient, String tableName) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
        String table = R2dbcPersistenceStore.validateTableName(tableName);
        this.deleteSql = """
                DELETE FROM %s \
                WHERE ctid IN ( \
                  SELECT ctid FROM %s \
                  WHERE expires_at IS NOT NULL AND expires_at < :before \
                  ORDER BY expires_at \
                  FOR UPDATE SKIP LOCKED \
                  LIMIT :limit \
                )""".formatted(table, table);
    }

    public Mono<Integer> deleteExpired(Instant before, int limit) {
        Objects.requireNonNull(before, "before");
        if (limit <= 0) {
            return Mono.error(new IllegalArgumentException("limit must be positive"));
        }
        return databaseClient
                .sql(deleteSql)
                .bind("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .bind("limit", limit)
                .fetch()
                .rowsUpdated()
                .map(Long::intValue);
    }
}
