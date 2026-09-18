-- Canonical schema for the Spring Boot Idempotency Starter (PostgreSQL).
-- ${TABLE} is replaced with the configured table name (default: idempotency_records).
-- ${INDEX_SUFFIX} is a sanitized table name used in index identifiers.
--
-- Copy this file into your Flyway/Liquibase migrations if you manage the schema
-- yourself (schema mode "validate" or "none").

CREATE TABLE IF NOT EXISTS ${TABLE} (

    operation        VARCHAR(128)  NOT NULL,
    idempotency_key  VARCHAR(255)  NOT NULL,

    request_hash     VARCHAR(128)  NOT NULL,

    status           VARCHAR(32)   NOT NULL,

    result_type      VARCHAR(255),
    result_payload   JSONB,

    error_code       VARCHAR(128),

    created_at       TIMESTAMPTZ   NOT NULL,
    completed_at     TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ,

    PRIMARY KEY (operation, idempotency_key)
);

-- Supports periodic physical cleanup of expired records:
--   DELETE FROM ${TABLE} WHERE expires_at IS NOT NULL AND expires_at < now();
CREATE INDEX IF NOT EXISTS idx_${INDEX_SUFFIX}_expires_at ON ${TABLE} (expires_at);
