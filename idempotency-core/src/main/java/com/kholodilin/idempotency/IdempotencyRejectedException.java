package com.kholodilin.idempotency;

import com.kholodilin.idempotency.jackson.Json;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * Thrown by {@link ExecutionResult#valueOrThrow()} when the outcome is a business
 * rejection. Intended to be handled once per application, e.g. by a
 * {@code @RestControllerAdvice} that maps it to an HTTP error response.
 *
 * <p>Never thrown by {@link IdempotencyService#execute} itself: inside the transaction a
 * rejection is a regular return value. Call {@code valueOrThrow()} only outside the
 * transactional method (typically in a controller), otherwise the transaction that has
 * just persisted the REJECTED outcome would be marked rollback-only.
 */
public class IdempotencyRejectedException extends IdempotencyException {

    private final String errorCode;
    private final transient JsonNode details;

    public IdempotencyRejectedException(String errorCode, JsonNode details) {
        super("Operation rejected: " + errorCode);
        this.errorCode = errorCode;
        this.details = details;
    }

    public String errorCode() {
        return errorCode;
    }

    public JsonNode details() {
        return details;
    }

    /**
     * Converts the rejection details into the given type.
     */
    public <T> @Nullable T detailsAs(Class<T> type) {
        return Json.treeToValue(details, type);
    }
}
