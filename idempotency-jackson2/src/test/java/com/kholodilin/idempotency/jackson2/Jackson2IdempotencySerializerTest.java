package com.kholodilin.idempotency.jackson2;

import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Jackson2IdempotencySerializerTest {

    private final Jackson2IdempotencySerializer serializer = new Jackson2IdempotencySerializer();

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

    @Test
    void serializeFailureIsWrappedAsIllegalArgumentException() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        Jackson2IdempotencySerializer failing = new Jackson2IdempotencySerializer(mapper);

        assertThatThrownBy(() -> failing.serialize("payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    @Test
    void deserializeFailureIsWrappedAsIllegalArgumentException() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.readValue(eq("{"), eq(PaymentResult.class))).thenThrow(new JsonProcessingException("boom") {});
        Jackson2IdempotencySerializer failing = new Jackson2IdempotencySerializer(mapper);

        assertThatThrownBy(() -> failing.deserialize("{", PaymentResult.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize")
                .hasCauseInstanceOf(JsonProcessingException.class);
    }
}
