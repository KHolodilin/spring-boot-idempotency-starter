package com.kholodilin.idempotency.spi;

import org.jspecify.annotations.Nullable;

/**
 * Serializes successful results and rejection details for persistence and deserializes
 * them on replay. The Jackson 3 implementation lives in {@code idempotency-jackson3};
 * replace with a custom bean if needed.
 *
 * <p>Payloads are JSON text ({@link String}), matching how records are stored.
 */
public interface IdempotencySerializer {

    String serialize(@Nullable Object value);

    <T> @Nullable T deserialize(@Nullable String value, Class<T> type);
}
