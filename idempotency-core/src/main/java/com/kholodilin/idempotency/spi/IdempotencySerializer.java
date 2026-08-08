package com.kholodilin.idempotency.spi;

import org.jspecify.annotations.Nullable;

/**
 * Serializes successful results for persistence and deserializes them on replay.
 * Default implementation is Jackson-based; replace with a custom bean if needed.
 */
public interface IdempotencySerializer {

    byte[] serialize(@Nullable Object value);

    <T> @Nullable T deserialize(byte[] value, Class<T> type);
}
