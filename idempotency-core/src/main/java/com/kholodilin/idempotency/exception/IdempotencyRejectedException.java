package com.kholodilin.idempotency.exception;

import java.util.Objects;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link ExecutionResult#valueOrThrow()} when the outcome is a business
 * rejection. Intended to be handled once per application, e.g. by a
 * {@code @RestControllerAdvice} that maps it to an HTTP error response.
 *
 * <p>Never thrown by {@link IdempotencyService} itself: inside the transaction a
 * rejection is a regular return value. Call {@code valueOrThrow()} only outside the
 * transactional method (typically in a controller), otherwise the transaction that has
 * just persisted the REJECTED outcome would be marked rollback-only.
 */
public class IdempotencyRejectedException extends IdempotencyException {

    private final String errorCode;
    private final transient @Nullable String detailsJson;

    public IdempotencyRejectedException(String errorCode, @Nullable String detailsJson) {
        super("Operation rejected: " + errorCode);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.detailsJson = detailsJson;
    }

    public String errorCode() {
        return errorCode;
    }

    /**
     * JSON text with rejection details, or {@code null} when absent.
     */
    public @Nullable String detailsJson() {
        return detailsJson;
    }

    /**
     * Converts the rejection details into the given type.
     */
    public <T> @Nullable T detailsAs(Class<T> type, IdempotencySerializer serializer) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(serializer, "serializer");
        if (detailsJson == null) {
            return null;
        }
        return serializer.deserialize(detailsJson, type);
    }
}
