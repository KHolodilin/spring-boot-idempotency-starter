package com.kholodilin.idempotency.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import com.kholodilin.idempotency.spi.PersistenceStore;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link PersistenceStore} backed by JDBC/PostgreSQL.
 *
 * <p>Participates in the caller's current transaction: no independent transactions are
 * ever opened, so idempotency state and business state commit atomically.
 *
 * <p>Concurrency is arbitrated by the primary key {@code (operation, idempotency_key)}:
 * {@link #acquire} uses {@code INSERT ... ON CONFLICT}, so a concurrent duplicate blocks
 * on the index entry until the first transaction commits or rolls back. Expired records
 * are taken over atomically by the same statement.
 */
public final class JdbcPersistenceStore implements PersistenceStore {

    public static final String DEFAULT_TABLE_NAME = "idempotency_records";

    private final JdbcClient jdbc;
    private final Clock clock;

    private final String findSql;
    private final String acquireSql;
    private final String completeSql;
    private final String rejectSql;

    public JdbcPersistenceStore(DataSource dataSource) {
        this(dataSource, DEFAULT_TABLE_NAME, Clock.systemUTC());
    }

    public JdbcPersistenceStore(DataSource dataSource, String tableName, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = Objects.requireNonNull(clock, "clock");
        String table = validateTableName(tableName);

        this.findSql = """
                SELECT operation, idempotency_key, request_hash, status, result_type, \
                result_payload, error_code, created_at, completed_at, expires_at \
                FROM %s \
                WHERE operation = ? AND idempotency_key = ? \
                AND (expires_at IS NULL OR expires_at > ?)""".formatted(table);

        this.acquireSql = """
                INSERT INTO %s AS t \
                (operation, idempotency_key, request_hash, status, created_at, expires_at) \
                VALUES (?, ?, ?, 'PROCESSING', ?, ?) \
                ON CONFLICT (operation, idempotency_key) DO UPDATE SET \
                request_hash = EXCLUDED.request_hash, \
                status = 'PROCESSING', \
                result_type = NULL, \
                result_payload = NULL, \
                error_code = NULL, \
                created_at = EXCLUDED.created_at, \
                completed_at = NULL, \
                expires_at = EXCLUDED.expires_at \
                WHERE t.expires_at IS NOT NULL AND t.expires_at <= EXCLUDED.created_at""".formatted(table);

        this.completeSql = """
                UPDATE %s SET status = 'COMPLETED', result_type = ?, result_payload = ?::jsonb, \
                completed_at = ? \
                WHERE operation = ? AND idempotency_key = ?""".formatted(table);

        this.rejectSql = """
                UPDATE %s SET status = 'REJECTED', error_code = ?, result_payload = ?::jsonb, \
                completed_at = ? \
                WHERE operation = ? AND idempotency_key = ?""".formatted(table);
    }

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyKey key) {
        return jdbc.sql(findSql)
                .param(1, key.operation())
                .param(2, key.key())
                .param(3, offset(clock.instant()))
                .query(JdbcPersistenceStore::mapRecord)
                .optional();
    }

    @Override
    public boolean acquire(IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt) {
        int rows = jdbc.sql(acquireSql)
                .param(1, key.operation())
                .param(2, key.key())
                .param(3, requestHash)
                .param(4, offset(createdAt))
                .param(5, offset(expiresAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
        return rows == 1;
    }

    @Override
    public void complete(
            IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt) {
        int rows = jdbc.sql(completeSql)
                .param(1, resultType, Types.VARCHAR)
                .param(2, resultPayload, Types.VARCHAR)
                .param(3, offset(completedAt))
                .param(4, key.operation())
                .param(5, key.key())
                .update();
        requireSingleRow(rows, key, "complete");
    }

    @Override
    public void reject(IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt) {
        int rows = jdbc.sql(rejectSql)
                .param(1, errorCode)
                .param(2, detailsPayload, Types.VARCHAR)
                .param(3, offset(completedAt))
                .param(4, key.operation())
                .param(5, key.key())
                .update();
        requireSingleRow(rows, key, "reject");
    }

    private static void requireSingleRow(int rows, IdempotencyKey key, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(
                    "Idempotency %s for %s affected %d rows, expected exactly 1".formatted(operation, key, rows));
        }
    }

    private static IdempotencyRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new IdempotencyRecord(
                new IdempotencyKey(rs.getString("operation"), rs.getString("idempotency_key")),
                IdempotencyStatus.valueOf(rs.getString("status")),
                rs.getString("request_hash"),
                rs.getString("result_type"),
                rs.getString("result_payload"),
                rs.getString("error_code"),
                instant(rs.getObject("created_at", OffsetDateTime.class)),
                instant(rs.getObject("completed_at", OffsetDateTime.class)),
                instant(rs.getObject("expires_at", OffsetDateTime.class)));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime offset(@Nullable Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
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
