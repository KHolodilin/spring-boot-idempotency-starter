package com.kholodilin.idempotency.exception;

import java.util.Map;

import com.kholodilin.idempotency.testsupport.TestIdempotencySerializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRejectedExceptionTest {

    private final TestIdempotencySerializer serializer = new TestIdempotencySerializer();

    @Test
    void exposesErrorCodeAndDetailsJson() {
        IdempotencyRejectedException ex = new IdempotencyRejectedException("GONE", "{\"a\":1}");

        assertThat(ex.errorCode()).isEqualTo("GONE");
        assertThat(ex.detailsJson()).isEqualTo("{\"a\":1}");
        assertThat(ex.getMessage()).contains("GONE");
    }

    @Test
    void detailsAsDeserializesViaSerializer() {
        String json = serializer.serialize(Map.of("balance", 200));
        IdempotencyRejectedException ex = new IdempotencyRejectedException("INSUFFICIENT_FUNDS", json);

        assertThat(ex.detailsAs(Map.class, serializer)).containsEntry("balance", 200);
    }

    @Test
    void nullDetailsJsonYieldsNullDetailsAs() {
        IdempotencyRejectedException ex = new IdempotencyRejectedException("GONE", null);

        assertThat(ex.detailsJson()).isNull();
        assertThat(ex.detailsAs(Map.class, serializer)).isNull();
    }

    @Test
    void requiresNonNullErrorCode() {
        assertThatThrownBy(() -> new IdempotencyRejectedException(null, null)).isInstanceOf(NullPointerException.class);
    }
}
