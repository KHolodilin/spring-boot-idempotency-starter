package com.kholodilin.idempotency.jackson;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonIdempotencySerializerTest {

    private final JacksonIdempotencySerializer serializer = new JacksonIdempotencySerializer();

    record PaymentResult(String paymentId, BigDecimal amount) {}

    @Test
    void roundTripsPojo() {
        PaymentResult original = new PaymentResult("pay-42", new BigDecimal("99.90"));

        String json = serializer.serialize(original);
        PaymentResult restored = serializer.deserialize(json, PaymentResult.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void serializesNullAsJsonNull() {
        assertThat(serializer.serialize(null)).isEqualTo("null");
    }

    @Test
    void deserializesJsonNullToNull() {
        assertThat(serializer.deserialize("null", PaymentResult.class)).isNull();
    }

    @Test
    void voidTypeAlwaysDeserializesToNull() {
        assertThat(serializer.deserialize("{}", Void.class)).isNull();
        assertThat(serializer.deserialize("{}", void.class)).isNull();
        assertThat(serializer.deserialize(null, PaymentResult.class)).isNull();
    }
}
