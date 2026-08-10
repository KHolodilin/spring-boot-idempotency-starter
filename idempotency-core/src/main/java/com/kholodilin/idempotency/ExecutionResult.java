package com.kholodilin.idempotency;

import java.util.Objects;
import java.util.function.Function;

import com.kholodilin.idempotency.exception.IdempotencyRejectedException;
import com.kholodilin.idempotency.jackson.Json;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/**
 * Outcome of an idempotent business operation.
 *
 * <p>A business rejection is a deterministic result of the operation, not an exception:
 * it is returned as {@link Rejected}, persisted with status {@code REJECTED} and replayed
 * as-is on duplicate requests. Any exception thrown from the business action is by
 * definition a technical failure: the transaction is rolled back and the exception is
 * rethrown, so a later retry can execute the operation from scratch.
 *
 * <p>Rejection details are always carried as a {@link JsonNode}, both on the first
 * execution and on replay, so the two are indistinguishable for the caller. Use
 * {@link Rejected#detailsAs(Class)} for typed access.
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
     */
    record Rejected<RS>(String errorCode, JsonNode details) implements ExecutionResult<RS> {

        public Rejected {
            Objects.requireNonNull(errorCode, "errorCode");
            if (details == null) {
                details = NullNode.getInstance();
            }
        }

        /**
         * Converts the rejection details into the given type. Different error codes may
         * use different detail types; pick the class based on {@link #errorCode()}.
         */
        public <T> @Nullable T detailsAs(Class<T> type) {
            return Json.treeToValue(details, type);
        }
    }

    static <RS> ExecutionResult<RS> success(@Nullable RS value) {
        return new Success<>(value);
    }

    static <RS> ExecutionResult<RS> rejected(String errorCode) {
        return new Rejected<>(errorCode, NullNode.getInstance());
    }

    /**
     * Creates a rejection outcome. {@code details} is converted to a {@link JsonNode}
     * immediately so that the first execution and a replay return the identical shape.
     */
    static <RS> ExecutionResult<RS> rejected(String errorCode, @Nullable Object details) {
        return new Rejected<>(errorCode, Json.valueToTree(details));
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
            case Rejected<RS> r -> throw new IdempotencyRejectedException(r.errorCode(), r.details());
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
