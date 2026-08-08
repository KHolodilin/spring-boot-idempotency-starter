package com.kholodilin.idempotency.spi;

import java.util.Optional;

import com.kholodilin.idempotency.IdempotencyKey;
import com.kholodilin.idempotency.IdempotencyRecord;

/**
 * Optional L2 (shared between application instances) cache of committed idempotency
 * records.
 *
 * <p>Only terminal, committed records are ever stored. Persistence remains the source
 * of truth. Implementations are expected to fail open: a cache infrastructure failure
 * must not fail the business operation.
 */
public interface DistributedCache {

    Optional<IdempotencyRecord> get(IdempotencyKey key);

    void put(IdempotencyKey key, IdempotencyRecord record);

    void evict(IdempotencyKey key);
}
