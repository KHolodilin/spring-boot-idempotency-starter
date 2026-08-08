package com.kholodilin.idempotency;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * Executes business operations idempotently.
 *
 * <p>Must be called inside an active database transaction: idempotency state and
 * business state are committed atomically in the same transaction.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>calculates the request fingerprint;</li>
 *   <li>looks the record up in local cache, distributed cache and persistence;</li>
 *   <li>if found with the same fingerprint — replays the stored outcome without
 *       executing the action; with a different fingerprint —
 *       throws {@link IdempotencyConflictException};</li>
 *   <li>if absent — acquires the key, executes the action and persists the outcome
 *       ({@code COMPLETED} or {@code REJECTED}) in the caller's transaction;</li>
 *   <li>after commit populates the cache layers.</li>
 * </ol>
 */
public interface IdempotencyService {

    /**
     * Executes {@code action} idempotently.
     *
     * @param operation      logical operation name, e.g. {@code CREATE_ORDER}
     * @param idempotencyKey client-provided idempotency key
     * @param request        request payload used to calculate the fingerprint
     * @param resultType     successful result type, used to deserialize a replayed result;
     *                       use {@code Void.class} for void-like operations
     * @param action         business action returning the outcome
     * @return the outcome of this execution, or the stored outcome of a previous execution
     * @throws IdempotencyConflictException if the key was already used with a different payload
     * @throws MissingTransactionException  if no transaction is active
     */
    <RQ, RS> ExecutionResult<RS> execute(
            String operation,
            String idempotencyKey,
            @Nullable RQ request,
            Class<RS> resultType,
            Supplier<ExecutionResult<RS>> action);
}
