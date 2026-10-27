package com.kholodilin.idempotency.core;

import com.kholodilin.idempotency.testsupport.InMemoryStore;
import com.kholodilin.idempotency.testsupport.TestFingerprintStrategy;
import com.kholodilin.idempotency.testsupport.TestIdempotencySerializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultIdempotencyServiceBuilderTest {

    @Test
    void buildRequiresSerializer() {
        assertThatThrownBy(() -> new DefaultIdempotencyServiceBuilder(new InMemoryStore())
                        .fingerprintStrategy(new TestFingerprintStrategy())
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("serializer");
    }

    @Test
    void buildRequiresFingerprintStrategy() {
        assertThatThrownBy(() -> new DefaultIdempotencyServiceBuilder(new InMemoryStore())
                        .serializer(new TestIdempotencySerializer())
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fingerprintStrategy");
    }

    @Test
    void buildSucceedsWhenSpiProvided() {
        DefaultIdempotencyService service = new DefaultIdempotencyServiceBuilder(new InMemoryStore())
                .serializer(new TestIdempotencySerializer())
                .fingerprintStrategy(new TestFingerprintStrategy())
                .requireActiveTransaction(false)
                .build();

        assertThat(service).isNotNull();
    }
}
