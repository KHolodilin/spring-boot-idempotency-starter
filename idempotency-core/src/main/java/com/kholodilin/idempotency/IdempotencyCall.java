package com.kholodilin.idempotency;

import java.time.Duration;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * Fluent configuration for a single idempotent execution.
 *
 * <p>Started via {@link IdempotencyService#operation(String)}. {@link #key(String)} is
 * required before {@link #execute(Class, Supplier)}. {@link #request(Object)} and
 * {@link #ttl(Duration)} are optional.
 */
public interface IdempotencyCall {

    /**
     * Sets the client-provided idempotency key for this execution.
     */
    IdempotencyCall key(String idempotencyKey);

    /**
     * Sets the request payload used to calculate the fingerprint. May be {@code null}.
     */
    <RQ> IdempotencyCall request(@Nullable RQ request);

    /**
     * Overrides the service-level persistence TTL for a new acquire on this call.
     *
     * <p>{@code null} means no expiry ({@code expires_at = null}) for this acquire.
     * Omitting this method keeps the service default TTL. Does not affect replay of an
     * already stored record.
     */
    IdempotencyCall ttl(@Nullable Duration ttl);

    /**
     * Executes the action idempotently with the configured operation/key/request/TTL.
     *
     * @param resultType successful result type for deserializing a replayed result;
     *                   use {@code Void.class} for void-like operations
     * @param action     business action returning the outcome
     * @return the outcome of this execution, or the stored outcome of a previous execution
     * @throws com.kholodilin.idempotency.exception.IdempotencyConflictException if the key
     *         was already used with a different payload
     * @throws com.kholodilin.idempotency.exception.MissingTransactionException if no
     *         transaction is active when required
     * @throws IllegalStateException if {@link #key(String)} was not set
     */
    <RS> ExecutionResult<RS> execute(Class<RS> resultType, Supplier<ExecutionResult<RS>> action);
}
