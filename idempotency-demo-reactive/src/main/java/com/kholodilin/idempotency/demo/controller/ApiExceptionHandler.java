package com.kholodilin.idempotency.demo.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import com.kholodilin.idempotency.exception.IdempotencyRejectedException;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Application-wide mapping of idempotency outcomes to HTTP. The same handlers cover
 * both the first execution and replays, because the library makes them indistinguishable.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

    private final IdempotencySerializer serializer;

    /**
     * Business rejection ({@code ExecutionResult.valueOrThrow()}): deterministic outcome,
     * replayed on duplicates.
     */
    @ExceptionHandler(IdempotencyRejectedException.class)
    public ResponseEntity<?> onRejected(IdempotencyRejectedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.errorCode());
        body.put("details", e.detailsAs(Object.class, serializer));
        return ResponseEntity.unprocessableEntity().body(body);
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
