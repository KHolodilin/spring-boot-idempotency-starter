package com.kholodilin.idempotency.reactive;

import java.time.Duration;
import java.util.function.Supplier;

import com.kholodilin.idempotency.ExecutionResult;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Fluent configuration for a single reactive idempotent execution.
 *
 * <p>Started via {@link ReactiveIdempotencyService#operation(String)}. {@link #key(String)}
 * is required before {@link #execute(Class, Supplier)}. {@link #request(Object)} and
 * {@link #ttl(Duration)} are optional.
 */
public interface ReactiveIdempotencyCall {

    /**
     * Sets the client-provided idempotency key for this execution.
     */
    ReactiveIdempotencyCall key(String idempotencyKey);

    /**
     * Sets the request payload used to calculate the fingerprint. May be {@code null}.
     */
    <RQ> ReactiveIdempotencyCall request(@Nullable RQ request);

    /**
     * Overrides the service-level persistence TTL for a new acquire on this call.
     *
     * <p>{@code null} means no expiry ({@code expires_at = null}) for this acquire.
     * Omitting this method keeps the service default TTL. Does not affect replay of an
     * already stored record.
     */
    ReactiveIdempotencyCall ttl(@Nullable Duration ttl);

    /**
     * Executes the action idempotently with the configured operation/key/request/TTL.
     *
     * @param resultType successful result type for deserializing a replayed result;
     *                   use {@code Void.class} for void-like operations
     * @param action     business action returning the outcome as a {@link Mono}
     * @return the outcome of this execution, or the stored outcome of a previous execution
     * @throws com.kholodilin.idempotency.exception.IdempotencyConflictException if the key
     *         was already used with a different payload
     * @throws com.kholodilin.idempotency.exception.MissingTransactionException if no
     *         transaction is active when required
     */
    <RS> Mono<ExecutionResult<RS>> execute(Class<RS> resultType, Supplier<Mono<ExecutionResult<RS>>> action);
}
