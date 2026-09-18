package com.kholodilin.idempotency.demo.repository;

import java.math.BigDecimal;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public class PaymentRepository {

    private final DatabaseClient databaseClient;

    public PaymentRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<Void> insert(String paymentId, String orderId, String recipient, BigDecimal amount) {
        return databaseClient
                .sql("INSERT INTO payments (payment_id, order_id, recipient, amount) "
                        + "VALUES (:paymentId, :orderId, :recipient, :amount)")
                .bind("paymentId", paymentId)
                .bind("orderId", orderId)
                .bind("recipient", recipient)
                .bind("amount", amount)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Long> count() {
        return databaseClient
                .sql("SELECT count(*) AS n FROM payments")
                .map((row, metadata) -> row.get("n", Long.class))
                .one();
    }
}
