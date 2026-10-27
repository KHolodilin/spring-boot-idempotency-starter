package com.kholodilin.idempotency;

import java.util.Objects;
import java.util.function.Function;

import com.kholodilin.idempotency.exception.IdempotencyRejectedException;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of an idempotent business operation.
 *
 * <p>A business rejection is a deterministic result of the operation, not an exception:
 * it is returned as {@link Rejected}, persisted with status {@code REJECTED} and replayed
 * as-is on duplicate requests. Any exception thrown from the business action is by
 * definition a technical failure: the transaction is rolled back and the exception is
 * rethrown, so a later retry can execute the operation from scratch.
 *
 * <p>Rejection details are carried as JSON text ({@link String}), both on the first
 * execution and on replay, so the two are indistinguishable for the caller. Use
 * {@link Rejected#detailsAs(Class, IdempotencySerializer)} for typed access.
 *
 * @param <RS> successful result type
 */
public sealed interface ExecutionResult<RS> permits ExecutionResult.Success, ExecutionResult.Rejected {

    /**
     * Successful outcome. {@code value} may be {@code null} for void-like operations
     * (e.g. Kafka event processing).
     */
    record Success<RS>(@Nullable RS value) implements ExecutionResult<RS> {}

    /**
     * Deterministic business rejection outcome.
     *
     * @param errorCode   stable business error code
     * @param detailsJson JSON text with rejection details, or {@code null} when absent
     */
    record Rejected<RS>(String errorCode, @Nullable String detailsJson) implements ExecutionResult<RS> {

        public Rejected {
            Objects.requireNonNull(errorCode, "errorCode");
        }

        /**
         * Converts the rejection details into the given type. Different error codes may
         * use different detail types; pick the class based on {@link #errorCode()}.
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

    static <RS> ExecutionResult<RS> success(@Nullable RS value) {
        return new Success<>(value);
    }

    /**
     * Creates a rejection outcome without details ({@code detailsJson} is {@code null}).
     */
    static <RS> ExecutionResult<RS> rejected(String errorCode) {
        return new Rejected<>(errorCode, null);
    }

    /**
     * Creates a rejection outcome with pre-serialized JSON details.
     */
    static <RS> ExecutionResult<RS> rejected(String errorCode, @Nullable String detailsJson) {
        return new Rejected<>(errorCode, detailsJson);
    }

    /**
     * Creates a rejection outcome. {@code details} is serialized immediately so that the
     * first execution and a replay return the identical JSON text. A {@code null}
     * {@code details} value yields {@code null} {@code detailsJson} (serializer is not
     * invoked).
     */
    static <RS> ExecutionResult<RS> rejected(
            String errorCode, @Nullable Object details, IdempotencySerializer serializer) {
        Objects.requireNonNull(serializer, "serializer");
        String detailsJson = details == null ? null : serializer.serialize(details);
        return new Rejected<>(errorCode, detailsJson);
    }

    /**
     * Unwraps the outcome: returns the value for {@link Success}, throws
     * {@link IdempotencyRejectedException} for {@link Rejected}.
     *
     * <p><strong>Call only outside the transactional method</strong> (typically in a
     * controller). Throwing inside {@code @Transactional} would mark the transaction
     * rollback-only and discard the just-persisted REJECTED outcome.
     */
    default @Nullable RS valueOrThrow() {
        return switch (this) {
            case Success<RS> s -> s.value();
            case Rejected<RS> r -> throw new IdempotencyRejectedException(r.errorCode(), r.detailsJson());
        };
    }

    /**
     * Functional dispatch without an explicit {@code switch}.
     */
    default <T> T fold(Function<? super RS, ? extends T> onSuccess, Function<Rejected<RS>, ? extends T> onRejected) {
        return switch (this) {
            case Success<RS> s -> onSuccess.apply(s.value());
            case Rejected<RS> r -> onRejected.apply(r);
        };
    }

    /**
     * @return {@code true} if this outcome is a {@link Success}
     */
    default boolean isSuccess() {
        return this instanceof Success<RS>;
    }
}
