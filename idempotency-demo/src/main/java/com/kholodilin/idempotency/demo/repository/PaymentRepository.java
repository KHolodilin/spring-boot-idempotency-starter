package com.kholodilin.idempotency.demo.repository;

import java.math.BigDecimal;
import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private final JdbcClient jdbc;

    public PaymentRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void insert(String paymentId, String orderId, String recipient, BigDecimal amount) {
        jdbc.sql("INSERT INTO payments (payment_id, order_id, recipient, amount) VALUES (?, ?, ?, ?)")
                .param(1, paymentId)
                .param(2, orderId)
                .param(3, recipient)
                .param(4, amount)
                .update();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM payments").query(Long.class).single();
    }
}
