package com.kholodilin.idempotency.spi;

import java.time.Instant;
import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import org.jspecify.annotations.Nullable;

/**
 * Source of truth for idempotency records.
 *
 * <p>All mutating operations must participate in the caller's current database
 * transaction — implementations must never open an independent transaction, otherwise
 * the atomicity of business state and idempotency state is broken.
 *
 * <p>A row present for {@code (operation, idempotency_key)} means the key is taken.
 * Physical removal of expired rows is handled separately by
 * {@link IdempotencyPersistenceCleanup}; this store does not filter by {@code expires_at}
 * on the request path.
 */
public interface PersistenceStore {

    /**
     * Finds a record by primary key. Any existing row is returned regardless of
     * {@code expires_at}.
     */
    Optional<IdempotencyRecord> find(IdempotencyKey key);

    /**
     * Registers a new {@code PROCESSING} record. Concurrency arbitration relies on the
     * primary key {@code (operation, idempotency_key)}: only one concurrent transaction
     * can insert a given key ({@code INSERT ... ON CONFLICT DO NOTHING}).
     *
     * @return {@code true} if the record was registered by this call,
     *         {@code false} if a record for the key already exists
     */
    boolean acquire(IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt);

    /**
     * Marks the record as {@code COMPLETED} with the serialized successful result.
     * Must affect exactly one {@code PROCESSING} row.
     */
    void complete(IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt);

    /**
     * Marks the record as {@code REJECTED} with the business rejection outcome.
     * Must affect exactly one {@code PROCESSING} row.
     */
    void reject(IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt);
}
