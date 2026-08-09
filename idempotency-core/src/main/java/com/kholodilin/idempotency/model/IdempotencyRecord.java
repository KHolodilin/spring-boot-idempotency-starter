package com.kholodilin.idempotency.model;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Persistent state of one idempotent operation execution.
 *
 * <p>Payloads are stored as JSON text so that the record can be transported through
 * any cache or persistence implementation without knowledge of user types.
 *
 * @param key           operation + idempotency key
 * @param status        current status
 * @param requestHash   request fingerprint the record was created with
 * @param resultType    fully-qualified class name of the successful result (COMPLETED only)
 * @param resultPayload JSON payload: successful result for COMPLETED, rejection details for REJECTED
 * @param errorCode     business rejection code (REJECTED only)
 * @param createdAt     when the record was acquired
 * @param completedAt   when the terminal status was reached
 * @param expiresAt     after this instant the record is treated as absent
 */
public record IdempotencyRecord(
        IdempotencyKey key,
        IdempotencyStatus status,
        String requestHash,
        @Nullable String resultType,
        @Nullable String resultPayload,
        @Nullable String errorCode,
        Instant createdAt,
        @Nullable Instant completedAt,
        @Nullable Instant expiresAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static IdempotencyRecord processing(
            IdempotencyKey key, String requestHash, Instant createdAt, @Nullable Instant expiresAt) {
        return new IdempotencyRecord(
                key, IdempotencyStatus.PROCESSING, requestHash, null, null, null, createdAt, null, expiresAt);
    }

    public IdempotencyRecord completed(
            @Nullable String resultType, @Nullable String resultPayload, Instant completedAt) {
        return new IdempotencyRecord(
                key,
                IdempotencyStatus.COMPLETED,
                requestHash,
                resultType,
                resultPayload,
                null,
                createdAt,
                completedAt,
                expiresAt);
    }

    public IdempotencyRecord rejected(String errorCode, @Nullable String detailsPayload, Instant completedAt) {
        return new IdempotencyRecord(
                key,
                IdempotencyStatus.REJECTED,
                requestHash,
                null,
                detailsPayload,
                errorCode,
                createdAt,
                completedAt,
                expiresAt);
    }

    /**
     * @return {@code true} if the record has an expiration instant that is not after {@code now}
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
