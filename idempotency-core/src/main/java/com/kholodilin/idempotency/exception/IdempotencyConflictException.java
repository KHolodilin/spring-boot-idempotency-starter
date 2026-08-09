package com.kholodilin.idempotency.exception;

import com.kholodilin.idempotency.model.IdempotencyKey;

/**
 * Thrown when an idempotency key is reused for the same operation with a different
 * request payload (fingerprint mismatch). One idempotency key must not be used for
 * different payloads of the same operation.
 */
public class IdempotencyConflictException extends IdempotencyException {

    private final IdempotencyKey key;
    private final String storedFingerprint;
    private final String requestFingerprint;

    public IdempotencyConflictException(IdempotencyKey key, String storedFingerprint, String requestFingerprint) {
        super("Idempotency key '%s' of operation '%s' was already used with a different request payload"
                .formatted(key.key(), key.operation()));
        this.key = key;
        this.storedFingerprint = storedFingerprint;
        this.requestFingerprint = requestFingerprint;
    }

    public IdempotencyKey key() {
        return key;
    }

    public String storedFingerprint() {
        return storedFingerprint;
    }

    public String requestFingerprint() {
        return requestFingerprint;
    }
}
