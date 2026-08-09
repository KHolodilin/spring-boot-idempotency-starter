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
 */
public interface PersistenceStore {

    /**
     * Finds a record by key. Expired records ({@code expires_at} in the past) are
     * treated as absent.
     */
    Optional<IdempotencyRecord> find(IdempotencyKey key);

    /**
     * Registers a new {@code PROCESSING} record. Concurrency arbitration relies on the
     * primary key {@code (operation, idempotency_key)}: only one concurrent transaction
     * can register a given key (e.g. {@code INSERT ... ON CONFLICT DO NOTHING}).
     *
     * @return {@code true} if the record was registered by this call,
     *         {@code false} if a record for the key already exists
     */
    boolean acquire(IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt);

    /**
     * Marks the record as {@code COMPLETED} with the serialized successful result.
     */
    void complete(IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt);

    /**
     * Marks the record as {@code REJECTED} with the business rejection outcome.
     */
    void reject(IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt);
}
