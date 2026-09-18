package com.kholodilin.idempotency.r2dbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

/**
 * Creates or validates the idempotency table according to the configured {@link SchemaMode}.
 */
public final class R2dbcSchemaManager {

    private static final String SCHEMA_RESOURCE = "/com/kholodilin/idempotency/r2dbc/idempotency-records.sql";

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

    private final DatabaseClient databaseClient;
    private final String tableName;
    private final SchemaMode mode;

    public R2dbcSchemaManager(DatabaseClient databaseClient, String tableName, SchemaMode mode) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient");
        this.tableName = R2dbcPersistenceStore.validateTableName(tableName);
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Mono<Void> initialize() {
        return switch (mode) {
            case CREATE -> create();
            case VALIDATE -> validate();
            case NONE -> Mono.empty();
        };
    }

    /**
     * Blocking startup hook for Spring {@code initMethod}.
     */
    public void initializeBlocking() {
        initialize().block();
    }

    private Mono<Void> create() {
        Mono<Void> chain = Mono.empty();
        for (String statement : canonicalDdl(tableName).split(";")) {
            String sql = statement.strip();
            if (!sql.isEmpty()) {
                chain = chain.then(databaseClient.sql(sql).fetch().rowsUpdated().then());
            }
        }
        return chain;
    }

    private Mono<Void> validate() {
        return databaseClient
                .sql("SELECT to_regclass($1) IS NOT NULL AS present")
                .bind(0, tableName)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one()
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new IllegalStateException(
                                ("Idempotency table '%s' does not exist. Create it from the canonical schema "
                                                + "(classpath:%s) or switch idempotency.persistence.schema.mode to 'create'.")
                                        .formatted(tableName, SCHEMA_RESOURCE)));
                    }
                    String unqualified =
                            tableName.contains(".") ? tableName.substring(tableName.indexOf('.') + 1) : tableName;
                    return databaseClient
                            .sql("SELECT column_name FROM information_schema.columns WHERE table_name = $1")
                            .bind(0, unqualified.toLowerCase(Locale.ROOT))
                            .map((row, metadata) -> row.get("column_name", String.class))
                            .all()
                            .collectList()
                            .flatMap(columns -> {
                                Set<String> missing = new TreeSet<>(REQUIRED_COLUMNS);
                                columns.forEach(c -> missing.remove(c.toLowerCase(Locale.ROOT)));
                                if (!missing.isEmpty()) {
                                    return Mono.error(new IllegalStateException(
                                            "Idempotency table '%s' is missing required columns %s. Expected canonical schema: classpath:%s"
                                                    .formatted(tableName, missing, SCHEMA_RESOURCE)));
                                }
                                return Mono.empty();
                            });
                });
    }

    /**
     * Canonical DDL with the table name substituted.
     */
    public static String canonicalDdl(String tableName) {
        String table = R2dbcPersistenceStore.validateTableName(tableName);
        String indexSuffix = table.replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
        try (InputStream in = R2dbcSchemaManager.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            Objects.requireNonNull(in, "canonical schema resource not found: " + SCHEMA_RESOURCE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("${TABLE}", table)
                    .replace("${INDEX_SUFFIX}", indexSuffix);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read canonical schema resource", e);
        }
    }
}
