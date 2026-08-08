package com.kholodilin.idempotency.demo.model;

import java.math.BigDecimal;

/**
 * Successful payment outcome; stored as the idempotency result payload and replayed
 * on duplicate requests.
 */
public record PaymentResult(String paymentId, String orderId, BigDecimal amount, String status) {
}
