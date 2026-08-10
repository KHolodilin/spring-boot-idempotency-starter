package com.kholodilin.idempotency.spi;

import java.util.Optional;

import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;

/**
 * Optional L2 (shared between application instances) cache of committed idempotency
 * records.
 *
 * <p>Only terminal, committed records are ever stored. Persistence remains the source
 * of truth. Fail-open on cache infrastructure errors is the recommended default so a
 * Redis outage does not fail the business operation; concrete implementations may expose
 * a stricter fail-fast policy as an opt-in.
 */
public interface DistributedCache {

    Optional<IdempotencyRecord> get(IdempotencyKey key);

    void put(IdempotencyKey key, IdempotencyRecord record);

    void evict(IdempotencyKey key);
}
