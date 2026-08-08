package com.kholodilin.idempotency.jackson;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonIdempotencySerializerTest {

    private final JacksonIdempotencySerializer serializer = new JacksonIdempotencySerializer();

    record PaymentResult(String paymentId, BigDecimal amount) {
    }

    @Test
    void roundTripsPojo() {
        PaymentResult original = new PaymentResult("pay-42", new BigDecimal("99.90"));

        byte[] bytes = serializer.serialize(original);
        PaymentResult restored = serializer.deserialize(bytes, PaymentResult.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void serializesNullAsJsonNull() {
        assertThat(new String(serializer.serialize(null), StandardCharsets.UTF_8)).isEqualTo("null");
    }

    @Test
    void deserializesJsonNullToNull() {
        assertThat(serializer.deserialize("null".getBytes(StandardCharsets.UTF_8), PaymentResult.class)).isNull();
    }

    @Test
    void voidTypeAlwaysDeserializesToNull() {
        assertThat(serializer.deserialize("{}".getBytes(StandardCharsets.UTF_8), Void.class)).isNull();
    }
}
