package com.kholodilin.idempotency.model;

/**
 * Identity of an idempotent operation execution: {@code operation + idempotencyKey}.
 *
 * <p>The same idempotency key used with different operations identifies different
 * idempotency records.
 *
 * @param operation logical operation name, e.g. {@code CREATE_ORDER}
 * @param key       client-provided idempotency key, e.g. REST {@code Idempotency-Key}
 *                  header value or Kafka event id
 */
public record IdempotencyKey(String operation, String key) {

    public IdempotencyKey {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
    }
}
