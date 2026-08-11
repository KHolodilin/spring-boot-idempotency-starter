package com.kholodilin.idempotency;

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
 *       throws {@link com.kholodilin.idempotency.exception.IdempotencyConflictException};</li>
 *   <li>if absent — acquires the key, executes the action and persists the outcome
 *       ({@code COMPLETED} or {@code REJECTED}) in the caller's transaction;</li>
 *   <li>after commit populates the cache layers.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * idempotencyService
 *     .operation("CREATE_PAYMENT")
 *     .key(idempotencyKey)
 *     .request(request)
 *     .ttl(Duration.ofDays(30)) // optional per-call override
 *     .execute(PaymentResult.class, () -> { ... });
 * }</pre>
 */
public interface IdempotencyService {

    /**
     * Starts a fluent idempotent call for the given logical operation name
     * (e.g. {@code CREATE_ORDER}).
     */
    IdempotencyCall operation(String operation);
}
