package com.kholodilin.idempotency.r2dbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableNameValidationTest {

    @Test
    void acceptsSimpleAndSchemaQualifiedNames() {
        assertThat(R2dbcPersistenceStore.validateTableName("idempotency_records"))
                .isEqualTo("idempotency_records");
        assertThat(R2dbcPersistenceStore.validateTableName("billing.idempotency_records"))
                .isEqualTo("billing.idempotency_records");
    }

    @Test
    void rejectsSqlInjectionAttempts() {
        assertThatThrownBy(() -> R2dbcPersistenceStore.validateTableName("records; DROP TABLE users"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> R2dbcPersistenceStore.validateTableName("records--"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalDdlSubstitutesTableNameAndIndexSuffix() {
        String ddl = R2dbcSchemaManager.canonicalDdl("billing.idempotency_records");

        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS billing.idempotency_records");
        assertThat(ddl).contains("idx_billing_idempotency_records_expires_at");
        assertThat(ddl).doesNotContain("${TABLE}").doesNotContain("${INDEX_SUFFIX}");
    }
}
