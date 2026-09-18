package com.kholodilin.idempotency.reactive.spi;

import java.time.Instant;
import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Reactive source of truth for idempotency records.
 *
 * <p>All mutating operations must participate in the caller's current R2DBC
 * transaction — implementations must never open an independent transaction.
 */
public interface ReactivePersistenceStore {

    /**
     * Finds a record by primary key. Any existing row is returned regardless of
     * {@code expires_at}. Completes empty-optional when absent.
     */
    Mono<Optional<IdempotencyRecord>> find(IdempotencyKey key);

    /**
     * Registers a new {@code PROCESSING} record via {@code INSERT ... ON CONFLICT DO NOTHING}.
     *
     * @return {@code true} if the record was registered by this call,
     *         {@code false} if a record for the key already exists
     */
    Mono<Boolean> acquire(IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt);

    /**
     * Marks the record as {@code COMPLETED} with the serialized successful result.
     * Must affect exactly one {@code PROCESSING} row.
     */
    Mono<Void> complete(
            IdempotencyKey key, @Nullable String resultType, @Nullable String resultPayload, Instant completedAt);

    /**
     * Marks the record as {@code REJECTED} with the business rejection outcome.
     * Must affect exactly one {@code PROCESSING} row.
     */
    Mono<Void> reject(IdempotencyKey key, String errorCode, @Nullable String detailsPayload, Instant completedAt);
}
