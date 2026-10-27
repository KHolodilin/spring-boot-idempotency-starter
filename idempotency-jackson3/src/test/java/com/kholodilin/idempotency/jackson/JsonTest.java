package com.kholodilin.idempotency.jackson;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {

    @Test
    void mapperIsReusable() {
        assertThat(Json.mapper()).isSameAs(Json.mapper());
    }

    @Test
    void valueToTreeNullIsNullNode() {
        assertThat(Json.valueToTree(null)).isInstanceOf(NullNode.class);
    }

    @Test
    void valueToTreePassesThroughJsonNode() {
        JsonNode node = Json.mapper().valueToTree(Map.of("a", 1));
        assertThat(Json.valueToTree(node)).isSameAs(node);
    }

    @Test
    void valueToTreeConvertsPojo() {
        assertThat(Json.valueToTree(Map.of("k", "v")).get("k").asString()).isEqualTo("v");
    }
}
