package com.kholodilin.idempotency.reactive;

/**
 * Executes business operations idempotently on the reactive stack.
 *
 * <p>Must be subscribed inside an active R2DBC transaction: idempotency state and
 * business state are committed atomically in the same transaction.
 *
 * <p>Behaviour matches {@link com.kholodilin.idempotency.IdempotencyService}: cache
 * lookup, {@code INSERT ... ON CONFLICT DO NOTHING}, replay or execute, persist the
 * outcome, populate caches after commit.
 *
 * <p>Usage:
 * <pre>{@code
 * return transactionalOperator.transactional(
 *     idempotencyService
 *         .operation("CREATE_PAYMENT")
 *         .key(idempotencyKey)
 *         .request(request)
 *         .execute(PaymentResult.class, () -> doCreatePayment(request)));
 * }</pre>
 */
public interface ReactiveIdempotencyService {

    /**
     * Starts a fluent idempotent call for the given logical operation name
     * (e.g. {@code CREATE_ORDER}).
     */
    ReactiveIdempotencyCall operation(String operation);
}
