package com.kholodilin.idempotency.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Creates or validates the idempotency table according to the configured {@link SchemaMode}.
 *
 * <p>The canonical DDL ships as {@code com/kholodilin/idempotency/jdbc/idempotency-records.sql}
 * on the classpath; copy it into your own Flyway/Liquibase migrations when the schema is
 * managed externally.
 */
public final class JdbcSchemaManager {

    private static final String SCHEMA_RESOURCE = "/com/kholodilin/idempotency/jdbc/idempotency-records.sql";

    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "operation",
            "idempotency_key",
            "request_hash",
            "status",
            "result_type",
            "result_payload",
            "error_code",
            "created_at",
            "completed_at",
            "expires_at");

    private final JdbcClient jdbc;
    private final String tableName;
    private final SchemaMode mode;

    public JdbcSchemaManager(DataSource dataSource, String tableName, SchemaMode mode) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.tableName = JdbcPersistenceStore.validateTableName(tableName);
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public void initialize() {
        switch (mode) {
            case CREATE -> create();
            case VALIDATE -> validate();
            case NONE -> {}
        }
    }

    private void create() {
        for (String statement : canonicalDdl(tableName).split(";")) {
            String sql = statement.strip();
            if (!sql.isEmpty()) {
                jdbc.sql(sql).update();
            }
        }
    }

    private void validate() {
        Boolean exists = jdbc.sql("SELECT to_regclass(?) IS NOT NULL")
                .param(1, tableName)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            throw new IllegalStateException(
                    ("Idempotency table '%s' does not exist. Create it from the canonical schema "
                                    + "(classpath:%s) or switch idempotency.persistence.schema.mode to 'create'.")
                            .formatted(tableName, SCHEMA_RESOURCE));
        }

        String unqualified = tableName.contains(".") ? tableName.substring(tableName.indexOf('.') + 1) : tableName;
        List<String> columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_name = ?")
                .param(1, unqualified.toLowerCase(Locale.ROOT))
                .query(String.class)
                .list();

        Set<String> missing = new TreeSet<>(REQUIRED_COLUMNS);
        columns.forEach(c -> missing.remove(c.toLowerCase(Locale.ROOT)));
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Idempotency table '%s' is missing required columns %s. Expected canonical schema: classpath:%s"
                            .formatted(tableName, missing, SCHEMA_RESOURCE));
        }
    }

    /**
     * Canonical DDL with the table name substituted.
     */
    public static String canonicalDdl(String tableName) {
        String table = JdbcPersistenceStore.validateTableName(tableName);
        String indexSuffix = table.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        try (InputStream in = JdbcSchemaManager.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            Objects.requireNonNull(in, "canonical schema resource not found: " + SCHEMA_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("${TABLE}", table)
                    .replace("${INDEX_SUFFIX}", indexSuffix);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read canonical schema resource", e);
        }
    }
}
