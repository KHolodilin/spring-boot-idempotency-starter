package com.kholodilin.idempotency.testsupport;

import com.kholodilin.idempotency.spi.IdempotencySerializer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only {@link IdempotencySerializer} backed by Jackson 3. Production code uses
 * {@code idempotency-jackson3}; core keeps Jackson off the compile/runtime classpath.
 */
public final class TestIdempotencySerializer implements IdempotencySerializer {

    private final JsonMapper mapper;

    public TestIdempotencySerializer() {
        this(JsonMapper.builder().build());
    }

    public TestIdempotencySerializer(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String serialize(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        return mapper.writeValueAsString(value);
    }

    @Override
    public <T> @Nullable T deserialize(@Nullable String value, Class<T> type) {
        if (type == Void.class || type == void.class || value == null) {
            return null;
        }
        return mapper.readValue(value, type);
    }
}
