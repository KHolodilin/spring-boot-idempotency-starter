package com.kholodilin.idempotency.demo.controller;

import java.util.Map;

import com.kholodilin.idempotency.IdempotencyConflictException;
import com.kholodilin.idempotency.IdempotencyRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Application-wide mapping of idempotency outcomes to HTTP. The same handlers cover
 * both the first execution and replays, because the library makes them indistinguishable.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Business rejection ({@code ExecutionResult.valueOrThrow()}): deterministic outcome,
     * replayed on duplicates.
     */
    @ExceptionHandler(IdempotencyRejectedException.class)
    public ResponseEntity<?> onRejected(IdempotencyRejectedException e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of(
                        "code", e.errorCode(),
                        "details", e.details()));
    }

    /**
     * Same idempotency key reused with a different payload.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<?> onConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "IDEMPOTENCY_KEY_CONFLICT", "message", e.getMessage()));
    }
}
