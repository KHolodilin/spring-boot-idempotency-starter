package com.kholodilin.idempotency.demo.model;

import java.math.BigDecimal;

public record RefundRequest(String paymentId, BigDecimal amount) {}
