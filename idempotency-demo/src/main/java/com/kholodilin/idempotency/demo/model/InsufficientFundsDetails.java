package com.kholodilin.idempotency.demo.model;

import java.math.BigDecimal;

/**
 * Rejection details of the {@code INSUFFICIENT_FUNDS} business error.
 */
public record InsufficientFundsDetails(BigDecimal requestedAmount, BigDecimal availableBalance) {
}
