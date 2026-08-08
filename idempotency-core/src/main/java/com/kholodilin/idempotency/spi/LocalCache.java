package com.kholodilin.idempotency.spi;

import java.util.Optional;

import com.kholodilin.idempotency.IdempotencyKey;
import com.kholodilin.idempotency.IdempotencyRecord;

/**
 * Optional L1 (in-process) cache of committed idempotency records.
 *
 * <p>Only terminal, committed records are ever stored. Persistence remains the source
 * of truth; the cache exists purely for performance and hot-key protection.
 */
public interface LocalCache {

    Optional<IdempotencyRecord> get(IdempotencyKey key);

    void put(IdempotencyKey key, IdempotencyRecord record);

    void evict(IdempotencyKey key);
}
