package com.kholodilin.idempotency.demo.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.IdempotencyService;
import com.kholodilin.idempotency.demo.model.CreatePaymentRequest;
import com.kholodilin.idempotency.demo.model.InsufficientFundsDetails;
import com.kholodilin.idempotency.demo.model.PaymentResult;
import com.kholodilin.idempotency.demo.model.RefundRequest;
import com.kholodilin.idempotency.demo.model.RefundResult;
import com.kholodilin.idempotency.demo.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service: the idempotency boundary of business operations.
 *
 * <p>Returns {@link ExecutionResult} as-is; mapping the outcome to HTTP is the
 * controller's responsibility.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** Demo account balance: payments above this amount are rejected. */
    static final BigDecimal BALANCE = new BigDecimal("1000.00");

    /** Refunds above this amount are rejected. */
    static final BigDecimal REFUND_LIMIT = new BigDecimal("500.00");

    private final IdempotencyService idempotencyService;
    private final PaymentRepository paymentRepository;
    private final FailureSimulator failureSimulator;

    @Transactional
    public ExecutionResult<PaymentResult> createPayment(String idempotencyKey, CreatePaymentRequest request) {
        return idempotencyService.execute(
                "CREATE_PAYMENT",
                idempotencyKey,
                request,
                PaymentResult.class,
                () -> doCreatePayment(idempotencyKey, request));
    }

    private ExecutionResult<PaymentResult> doCreatePayment(String idempotencyKey, CreatePaymentRequest request) {
        // a technical exception from here rolls the whole transaction back
        failureSimulator.maybeFail(request.recipient(), idempotencyKey);

        // deterministic business rejection: replayed on duplicate requests
        if (request.amount().compareTo(BALANCE) > 0) {
            return ExecutionResult.rejected(
                    "INSUFFICIENT_FUNDS", new InsufficientFundsDetails(request.amount(), BALANCE));
        }

        String paymentId = UUID.randomUUID().toString();
        paymentRepository.insert(paymentId, request.orderId(), request.recipient(), request.amount());
        return ExecutionResult.success(new PaymentResult(paymentId, request.orderId(), request.amount(), "CONFIRMED"));
    }

    @Transactional
    public ExecutionResult<RefundResult> refund(String idempotencyKey, RefundRequest request) {
        return idempotencyService.execute("REFUND_PAYMENT", idempotencyKey, request, RefundResult.class, () -> {
            if (request.amount().compareTo(REFUND_LIMIT) > 0) {
                return ExecutionResult.rejected("REFUND_LIMIT_EXCEEDED", request);
            }
            return ExecutionResult.success(
                    new RefundResult(UUID.randomUUID().toString(), request.paymentId(), request.amount(), "REFUNDED"));
        });
    }
}
