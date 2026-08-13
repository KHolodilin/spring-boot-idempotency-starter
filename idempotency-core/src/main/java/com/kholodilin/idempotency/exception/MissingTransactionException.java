package com.kholodilin.idempotency.exception;

import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.model.IdempotencyKey;

/**
 * Thrown when an {@link IdempotencyService} call is invoked without an active database
 * transaction. Idempotency state and business state must be committed atomically, which
 * requires the caller to run inside a transaction (e.g. a {@code @Transactional} method).
 */
public class MissingTransactionException extends IdempotencyException {

    public MissingTransactionException(IdempotencyKey key) {
        super(("No active transaction while executing operation '%s' with idempotency key '%s'. "
                        + "IdempotencyService.operation(...).execute(...) must be called inside an active "
                        + "transaction, e.g. from a @Transactional method.")
                .formatted(key.operation(), key.key()));
    }
}
