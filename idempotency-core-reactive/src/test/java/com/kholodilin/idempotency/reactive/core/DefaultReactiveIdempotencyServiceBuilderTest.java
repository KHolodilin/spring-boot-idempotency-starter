package com.kholodilin.idempotency.reactive.core;

import com.kholodilin.idempotency.reactive.spi.ReactivePersistenceStore;
import com.kholodilin.idempotency.testsupport.TestFingerprintStrategy;
import com.kholodilin.idempotency.testsupport.TestIdempotencySerializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DefaultReactiveIdempotencyServiceBuilderTest {

    @Test
    void buildRequiresSerializer() {
        assertThatThrownBy(() -> new DefaultReactiveIdempotencyServiceBuilder(mock(ReactivePersistenceStore.class))
                        .fingerprintStrategy(new TestFingerprintStrategy())
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("serializer");
    }

    @Test
    void buildRequiresFingerprintStrategy() {
        assertThatThrownBy(() -> new DefaultReactiveIdempotencyServiceBuilder(mock(ReactivePersistenceStore.class))
                        .serializer(new TestIdempotencySerializer())
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fingerprintStrategy");
    }

    @Test
    void buildSucceedsWhenSpiProvided() {
        DefaultReactiveIdempotencyService service = new DefaultReactiveIdempotencyServiceBuilder(
                        mock(ReactivePersistenceStore.class))
                .serializer(new TestIdempotencySerializer())
                .fingerprintStrategy(new TestFingerprintStrategy())
                .requireActiveTransaction(false)
                .build();

        assertThat(service).isNotNull();
    }
}
