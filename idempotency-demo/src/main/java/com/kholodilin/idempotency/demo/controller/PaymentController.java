package com.kholodilin.idempotency.demo.controller;

import java.util.Map;

import com.kholodilin.idempotency.demo.model.CreatePaymentRequest;
import com.kholodilin.idempotency.demo.model.PaymentResult;
import com.kholodilin.idempotency.demo.model.RefundRequest;
import com.kholodilin.idempotency.demo.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Primary style: {@code valueOrThrow()} unwraps the outcome outside the transaction;
     * business rejections become {@code IdempotencyRejectedException} handled once for
     * the whole application by {@link ApiExceptionHandler}.
     */
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResult createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody CreatePaymentRequest request) {
        return paymentService.createPayment(idempotencyKey, request).valueOrThrow();
    }

    /**
     * Alternative style: {@code fold()} maps both outcomes explicitly, useful when one
     * endpoint needs a non-standard rejection response.
     */
    @PostMapping("/refunds")
    public ResponseEntity<?> refund(
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody RefundRequest request) {
        return paymentService
                .refund(idempotencyKey, request)
                .fold(ResponseEntity::ok, rejected -> ResponseEntity.unprocessableEntity()
                        .body(Map.of(
                                "code", rejected.errorCode(),
                                "details", rejected.details())));
    }
}
