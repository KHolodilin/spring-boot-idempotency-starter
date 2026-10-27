package com.kholodilin.idempotency.jackson2;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link IdempotencySerializer} based on Jackson 2.
 */
public final class Jackson2IdempotencySerializer implements IdempotencySerializer {

    private final ObjectMapper mapper;

    public Jackson2IdempotencySerializer() {
        this(Json.mapper());
    }

    public Jackson2IdempotencySerializer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String serialize(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize idempotency payload", e);
        }
    }

    @Override
    public <T> @Nullable T deserialize(@Nullable String value, Class<T> type) {
        if (type == Void.class || type == void.class || value == null) {
            return null;
        }
        try {
            return mapper.readValue(value, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize idempotency payload", e);
        }
    }
}
