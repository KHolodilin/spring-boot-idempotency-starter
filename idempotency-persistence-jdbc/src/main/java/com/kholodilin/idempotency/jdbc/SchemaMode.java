package com.kholodilin.idempotency.jdbc;

/**
 * Schema management mode for the idempotency table.
 */
public enum SchemaMode {

    /**
     * Execute the canonical DDL at startup ({@code CREATE TABLE IF NOT EXISTS}).
     * Convenient for local development, demos and tests.
     */
    CREATE,

    /**
     * Verify that a compatible table exists and fail fast otherwise.
     * Recommended production mode: the schema itself is managed by the application's
     * own migration tool (Flyway/Liquibase) using the shipped canonical SQL.
     */
    VALIDATE,

    /**
     * Perform no schema operations at all.
     */
    NONE
}
