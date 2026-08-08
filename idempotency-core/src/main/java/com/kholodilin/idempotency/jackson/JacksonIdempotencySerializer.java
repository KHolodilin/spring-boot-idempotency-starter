package com.kholodilin.idempotency.jackson;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.kholodilin.idempotency.spi.IdempotencySerializer;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.json.JsonMapper;

/**
 * Default {@link IdempotencySerializer} based on Jackson 3.
 */
public final class JacksonIdempotencySerializer implements IdempotencySerializer {

    private final JsonMapper mapper;

    public JacksonIdempotencySerializer() {
        this(Json.mapper());
    }

    public JacksonIdempotencySerializer(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public byte[] serialize(@Nullable Object value) {
        if (value == null) {
            return "null".getBytes(StandardCharsets.UTF_8);
        }
        return mapper.writeValueAsBytes(value);
    }

    @Override
    public <T> @Nullable T deserialize(byte[] value, Class<T> type) {
        if (type == Void.class || type == void.class) {
            return null;
        }
        return mapper.readValue(value, type);
    }
}
