package com.kholodilin.idempotency.jackson2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.jspecify.annotations.Nullable;

/**
 * Shared Jackson 2 mapper used by {@link Jackson2IdempotencySerializer} and
 * {@link CanonicalJsonFingerprintStrategy}.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static ObjectMapper mapper() {
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
