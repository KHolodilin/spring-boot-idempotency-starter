package com.kholodilin.idempotency.r2dbc;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import io.r2dbc.spi.Readable;
import org.jspecify.annotations.Nullable;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * {@link ReactivePersistenceStore} backed by R2DBC/PostgreSQL.
 *
 * <p>Participates in the caller's current transaction: no independent transactions are
 * ever opened.
 */
public final class R2dbcPersistenceStore implements ReactivePersistenceStore {

    public static final String DEFAULT_TABLE_NAME = "idempotency_records";

    private final DatabaseClient databaseClient;
    private final String findSql;
    private final String acquireSql;
    private final String completeSql;
    private final String rejectSql;

    public R2dbcPersistenceStore(DatabaseClient databaseClient) {
        this(databaseClient, DEFAULT_TABLE_NAME);
    }

    public R2dbcPersistenceStore(DatabaseClient databaseClient, String tableName) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
        String table = validateTableName(tableName);

        this.findSql = """
                SELECT operation, idempotency_key, request_hash, status, result_type, \
                result_payload::text AS result_payload, error_code, created_at, completed_at, expires_at \
                FROM %s \
                WHERE operation = :operation AND idempotency_key = :idempotencyKey""".formatted(table);

        this.acquireSql = """
                INSERT INTO %s \
                (operation, idempotency_key, request_hash, status, created_at, expires_at) \
                VALUES (:operation, :idempotencyKey, :requestHash, 'PROCESSING', :createdAt, :expiresAt) \
                ON CONFLICT (operation, idempotency_key) DO NOTHING""".formatted(table);

        this.completeSql = """
                UPDATE %s SET status = 'COMPLETED', result_type = :resultType, result_payload = CAST(:resultPayload AS jsonb), \
                completed_at = :completedAt \
                WHERE operation = :operation AND idempotency_key = :idempotencyKey AND status = 'PROCESSING'""".formatted(table);

        this.rejectSql = """
                UPDATE %s SET status = 'REJECTED', error_code = :errorCode, result_payload = CAST(:detailsPayload AS jsonb), \
                completed_at = :completedAt \
                WHERE operation = :operation AND idempotency_key = :idempotencyKey AND status = 'PROCESSING'""".formatted(table);
    }

    @Override
    public Mono<Optional<IdempotencyRecord>> find(IdempotencyKey key) {
        return databaseClient
                .sql(findSql)
                .bind("operation", key.operation())
                .bind("idempotencyKey", key.key())
                .map((row, metadata) -> mapRecord(row))
                .one()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    @Override
    public Mono<Boolean> acquire(
            IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql(acquireSql)
                .bind("operation", key.operation())
                .bind("idempotencyKey", key.key())
                .bind("requestHash", requestHash)
                .bind("createdAt", offset(createdAt));
        spec = bindNullable(spec, "expiresAt", offset(expiresAt), OffsetDateTime.class);
        return spec.fetch().rowsUpdated().map(rows -> rows == 1);
    }

    @Override
    public Mono<Void> complete(
            IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql(completeSql)
                .bind("completedAt", offset(completedAt))
                .bind("operation", key.operation())
                .bind("idempotencyKey", key.key());
        spec = bindNullable(spec, "resultType", resultType, String.class);
        spec = bindNullableJson(spec, "resultPayload", resultPayload);
        return spec.fetch().rowsUpdated().flatMap(rows -> requireSingleRow(rows, key, "complete"));
    }

    @Override
    public Mono<Void> reject(
            IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient
                .sql(rejectSql)
                .bind("errorCode", errorCode)
                .bind("completedAt", offset(completedAt))
                .bind("operation", key.operation())
                .bind("idempotencyKey", key.key());
        spec = bindNullableJson(spec, "detailsPayload", detailsPayload);
        return spec.fetch().rowsUpdated().flatMap(rows -> requireSingleRow(rows, key, "reject"));
    }

    private static Mono<Void> requireSingleRow(long rows, IdempotencyKey key, String operation) {
        if (rows != 1) {
            return Mono.error(new IllegalStateException(
                    "Idempotency %s for %s affected %d rows, expected exactly 1".formatted(operation, key, rows)));
        }
        return Mono.empty();
    }

    private static IdempotencyRecord mapRecord(Readable row) {
        return new IdempotencyRecord(
                new IdempotencyKey(row.get("operation", String.class), row.get("idempotency_key", String.class)),
                IdempotencyStatus.valueOf(row.get("status", String.class)),
                row.get("request_hash", String.class),
                row.get("result_type", String.class),
                row.get("result_payload", String.class),
                row.get("error_code", String.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("completed_at", OffsetDateTime.class)),
                instant(row.get("expires_at", OffsetDateTime.class)));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime offset(@Nullable Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, @Nullable Object value, Class<?> type) {
        if (value == null) {
            return spec.bindNull(name, type);
        }
        return spec.bind(name, value);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableJson(
            DatabaseClient.GenericExecuteSpec spec, String name, @Nullable String value) {
        if (value == null) {
            return spec.bindNull(name, String.class);
        }
        return spec.bind(name, value);
    }

    /**
     * The table name is interpolated into SQL text, so restrict it to safe identifiers.
     */
    static String validateTableName(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?")) {
            throw new IllegalArgumentException(
                    "Invalid idempotency table name '%s': only [A-Za-z0-9_] identifiers with an optional schema prefix are allowed"
                            .formatted(tableName));
        }
        return tableName;
    }
}
