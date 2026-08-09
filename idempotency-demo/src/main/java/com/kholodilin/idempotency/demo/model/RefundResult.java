package com.kholodilin.idempotency.demo.model;

import java.math.BigDecimal;

public record RefundResult(String refundId, String paymentId, BigDecimal amount, String status) {}
