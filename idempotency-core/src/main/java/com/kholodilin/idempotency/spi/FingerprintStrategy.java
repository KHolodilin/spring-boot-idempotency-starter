package com.kholodilin.idempotency.spi;

import org.jspecify.annotations.Nullable;

/**
 * Calculates a deterministic fingerprint of a request payload.
 *
 * <p>The fingerprint guards against reusing one idempotency key for different payloads
 * of the same operation. Provide a custom implementation to exclude technical metadata
 * (trace ids, timestamps, correlation ids) from the fingerprint.
 */
public interface FingerprintStrategy {

    String calculate(@Nullable Object request);
}
