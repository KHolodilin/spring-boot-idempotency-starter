package com.kholodilin.idempotency;

/**
 * Base class for exceptions thrown by the idempotency library itself.
 */
public abstract class IdempotencyException extends RuntimeException {

    protected IdempotencyException(String message) {
        super(message);
    }

    protected IdempotencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
