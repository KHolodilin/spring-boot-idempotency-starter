package com.kholodilin.idempotency.jackson;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;

/**
 * Shared Jackson mapper used by {@link JacksonIdempotencySerializer} and
 * {@link CanonicalJsonFingerprintStrategy}.
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

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
}
