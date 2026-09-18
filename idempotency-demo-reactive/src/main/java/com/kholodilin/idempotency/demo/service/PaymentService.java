package com.kholodilin.idempotency.demo.service;

import java.math.BigDecimal;
import java.util.UUID;

import com.kholodilin.idempotency.ExecutionResult;
import com.kholodilin.idempotency.demo.model.CreatePaymentRequest;
import com.kholodilin.idempotency.demo.model.InsufficientFundsDetails;
import com.kholodilin.idempotency.demo.model.PaymentResult;
import com.kholodilin.idempotency.demo.model.RefundRequest;
import com.kholodilin.idempotency.demo.model.RefundResult;
import com.kholodilin.idempotency.demo.repository.PaymentRepository;
import com.kholodilin.idempotency.reactive.ReactiveIdempotencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Application service: the idempotency boundary of business operations.
 *
 * <p>Returns {@link ExecutionResult} as-is; mapping the outcome to HTTP is the
 * controller's responsibility. {@code @Transactional} is not enough on WebFlux —
 * the chain is wrapped with {@link TransactionalOperator}.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** Demo account balance: payments above this amount are rejected. */
    static final BigDecimal BALANCE = new BigDecimal("1000.00");

    /** Refunds above this amount are rejected. */
    static final BigDecimal REFUND_LIMIT = new BigDecimal("500.00");

    private final ReactiveIdempotencyService idempotencyService;
    private final PaymentRepository paymentRepository;
    private final FailureSimulator failureSimulator;
    private final TransactionalOperator transactionalOperator;

    public Mono<ExecutionResult<PaymentResult>> createPayment(String idempotencyKey, CreatePaymentRequest request) {
        return transactionalOperator.transactional(idempotencyService
                .operation("CREATE_PAYMENT")
                .key(idempotencyKey)
                .request(request)
                .execute(PaymentResult.class, () -> doCreatePayment(idempotencyKey, request)));
    }

    private Mono<ExecutionResult<PaymentResult>> doCreatePayment(String idempotencyKey, CreatePaymentRequest request) {
        return Mono.fromRunnable(() -> failureSimulator.maybeFail(request.recipient(), idempotencyKey))
                .then(Mono.defer(() -> {
                    if (request.amount().compareTo(BALANCE) > 0) {
                        return Mono.just(ExecutionResult.rejected(
                                "INSUFFICIENT_FUNDS", new InsufficientFundsDetails(request.amount(), BALANCE)));
                    }
                    String paymentId = UUID.randomUUID().toString();
                    return paymentRepository
                            .insert(paymentId, request.orderId(), request.recipient(), request.amount())
                            .thenReturn(ExecutionResult.success(
                                    new PaymentResult(paymentId, request.orderId(), request.amount(), "CONFIRMED")));
                }));
    }

    public Mono<ExecutionResult<RefundResult>> refund(String idempotencyKey, RefundRequest request) {
        return transactionalOperator.transactional(idempotencyService
                .operation("REFUND_PAYMENT")
                .key(idempotencyKey)
                .request(request)
                .execute(RefundResult.class, () -> {
                    if (request.amount().compareTo(REFUND_LIMIT) > 0) {
                        return Mono.just(ExecutionResult.rejected("REFUND_LIMIT_EXCEEDED", request));
                    }
                    return Mono.just(ExecutionResult.success(new RefundResult(
                            UUID.randomUUID().toString(), request.paymentId(), request.amount(), "REFUNDED")));
                }));
    }
}
