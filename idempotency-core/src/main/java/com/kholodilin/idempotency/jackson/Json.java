package com.kholodilin.idempotency.jackson;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/**
 * Internal shared Jackson mapper used for in-memory tree conversions
 * ({@code ExecutionResult.rejected(...)} details, replay of rejection details).
 *
 * <p>This mapper is intentionally not customizable: it only converts between user
 * objects and {@link JsonNode} trees. Persistence and cache serialization go through
 * the replaceable {@link com.kholodilin.idempotency.spi.IdempotencySerializer} SPI.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    public static JsonNode valueToTree(@Nullable Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        return MAPPER.valueToTree(value);
    }

    public static <T> @Nullable T treeToValue(@Nullable JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        return MAPPER.treeToValue(node, type);
    }

    public static JsonNode readTree(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        return MAPPER.readTree(json);
    }
}
