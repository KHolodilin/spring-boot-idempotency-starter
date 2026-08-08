package com.kholodilin.idempotency.redis;

/**
 * Behaviour of the Redis distributed cache when Redis is unavailable.
 */
public enum RedisCacheFailurePolicy {

    /**
     * Default. Redis failures are logged and swallowed: a lookup behaves as a cache miss
     * and the request proceeds to persistence. Cache infrastructure must never fail the
     * business operation.
     */
    FAIL_OPEN,

    /**
     * Redis failures propagate to the caller. Use only when serving without the
     * distributed cache is unacceptable.
     */
    FAIL_FAST
}
