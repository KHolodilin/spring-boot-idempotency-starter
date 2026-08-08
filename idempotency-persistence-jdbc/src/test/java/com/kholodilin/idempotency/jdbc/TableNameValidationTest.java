package com.kholodilin.idempotency.jdbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableNameValidationTest {

    @Test
    void acceptsSimpleAndSchemaQualifiedNames() {
        assertThat(JdbcPersistenceStore.validateTableName("idempotency_records"))
                .isEqualTo("idempotency_records");
        assertThat(JdbcPersistenceStore.validateTableName("billing.idempotency_records"))
                .isEqualTo("billing.idempotency_records");
    }

    @Test
    void rejectsSqlInjectionAttempts() {
        assertThatThrownBy(() -> JdbcPersistenceStore.validateTableName("records; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcPersistenceStore.validateTableName("records--"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalDdlSubstitutesTableNameAndIndexSuffix() {
        String ddl = JdbcSchemaManager.canonicalDdl("billing.idempotency_records");

        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS billing.idempotency_records");
        assertThat(ddl).contains("idx_billing_idempotency_records_expires_at");
        assertThat(ddl).doesNotContain("${TABLE}").doesNotContain("${INDEX_SUFFIX}");
    }
}
