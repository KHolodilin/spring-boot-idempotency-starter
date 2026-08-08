package com.kholodilin.idempotency.demo.model;

import java.math.BigDecimal;

/**
 * Payment creation request; also serves as the fingerprint payload of the operation.
 */
public record CreatePaymentRequest(String orderId, String recipient, BigDecimal amount) {
}
