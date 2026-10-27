package com.kholodilin.idempotency.jackson;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonFingerprintStrategyTest {

    private final CanonicalJsonFingerprintStrategy strategy = new CanonicalJsonFingerprintStrategy();

    record Command(String orderId, BigDecimal amount, String comment) {}

    record ParityPayload(String orderId, BigDecimal amount, List<String> tags, Map<String, Object> meta) {}

    @Test
    void sameObjectProducesSameFingerprint() {
        Command command = new Command("o-1", new BigDecimal("10.50"), "hi");

        assertThat(strategy.calculate(command)).isEqualTo(strategy.calculate(command));
    }

    @Test
    void mapKeyInsertionOrderDoesNotAffectFingerprint() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", 2);
        first.put("c", 3);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("c", 3);
        second.put("a", 1);
        second.put("b", 2);

        assertThat(strategy.calculate(first)).isEqualTo(strategy.calculate(second));
    }

    @Test
    void nestedMapOrderingIsCanonicalToo() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("outer", new LinkedHashMap<>(Map.of("x", 1, "y", 2)));
        first.put("list", List.of(Map.of("k", "v")));

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("list", List.of(Map.of("k", "v")));
        second.put("outer", new LinkedHashMap<>(Map.of("y", 2, "x", 1)));

        assertThat(strategy.calculate(first)).isEqualTo(strategy.calculate(second));
    }

    @Test
    void bigDecimalTrailingZerosAreNormalized() {
        Map<String, Object> first = Map.of("amount", new BigDecimal("1.0"));
        Map<String, Object> second = Map.of("amount", new BigDecimal("1.00"));

        assertThat(strategy.calculate(first)).isEqualTo(strategy.calculate(second));
    }

    @Test
    void doubleAndBigDecimalWithSameValueMatch() {
        Map<String, Object> first = Map.of("amount", 1.5d);
        Map<String, Object> second = Map.of("amount", new BigDecimal("1.50"));

        assertThat(strategy.calculate(first)).isEqualTo(strategy.calculate(second));
    }

    @Test
    void floatDoubleAndBigDecimalNormalizeTheSame() {
        assertThat(CanonicalJsonFingerprintStrategy.normalize(1.5f))
                .isEqualTo(CanonicalJsonFingerprintStrategy.normalize(1.5d))
                .isEqualTo(CanonicalJsonFingerprintStrategy.normalize(new BigDecimal("1.50")));
    }

    @Test
    void integerAndLongNormalizeTheSame() {
        assertThat(CanonicalJsonFingerprintStrategy.normalize(100))
                .isEqualTo(CanonicalJsonFingerprintStrategy.normalize(100L))
                .isEqualTo("100");
    }

    @Test
    void integerAndLongWithSameValueMatch() {
        Map<String, Object> first = Map.of("count", 100);
        Map<String, Object> second = Map.of("count", 100L);

        assertThat(strategy.calculate(first)).isEqualTo(strategy.calculate(second));
    }

    @Test
    void differentPayloadProducesDifferentFingerprint() {
        Command first = new Command("o-1", new BigDecimal("10.50"), "hi");
        Command second = new Command("o-1", new BigDecimal("10.51"), "hi");

        assertThat(strategy.calculate(first)).isNotEqualTo(strategy.calculate(second));
    }

    @Test
    void arrayOrderIsSignificant() {
        assertThat(strategy.calculate(Map.of("items", List.of(1, 2))))
                .isNotEqualTo(strategy.calculate(Map.of("items", List.of(2, 1))));
    }

    @Test
    void nullFieldAndAbsentFieldAreDifferent() {
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("a", 1);
        withNull.put("b", null);

        Map<String, Object> without = Map.of("a", 1);

        assertThat(strategy.calculate(withNull)).isNotEqualTo(strategy.calculate(without));
    }

    @Test
    void nullRequestHasStableFingerprint() {
        assertThat(strategy.calculate(null)).isEqualTo(strategy.calculate(null));
    }

    @Test
    void canonicalFormSortsKeysAndNormalizesNumbers() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("b", new BigDecimal("2.00"));
        payload.put("a", "text \"quoted\"");

        String canonical = strategy.canonicalize(Json.valueToTree(payload));

        assertThat(canonical).isEqualTo("{\"a\":\"text \\\"quoted\\\"\",\"b\":2}");
    }

    @Test
    void specialCharactersAreEscapedDeterministically() {
        String canonical = strategy.canonicalize(Json.valueToTree(Map.of("s", "line1\nline2\ttab")));

        assertThat(canonical).isEqualTo("{\"s\":\"line1\\nline2\\ttab\"}");
    }

    @Test
    void fingerprintIsSha256Hex() {
        assertThat(strategy.calculate(Map.of("a", 1))).matches("[0-9a-f]{64}");
    }

    @Test
    void unknownAlgorithmIsRejectedEagerly() {
        assertThatThrownBy(() -> new CanonicalJsonFingerprintStrategy("NO-SUCH-ALGO"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void booleanAndEmptyArrayAreCanonicalized() {
        assertThat(strategy.canonicalize(Json.valueToTree(Map.of("ok", true, "items", List.of()))))
                .isEqualTo("{\"items\":[],\"ok\":true}");
    }

    @Test
    void controlCharactersAndCommonEscapesAreEncoded() {
        String withControls = "a\b\f\r" + ((char) 0x01);
        String canonical = strategy.canonicalize(Json.valueToTree(Map.of("s", withControls)));

        assertThat(canonical).isEqualTo("{\"s\":\"a\\b\\f\\r\\u0001\"}");
    }

    @Test
    void binaryNodeFallsBackToJacksonToString() {
        byte[] bytes = new byte[] {1, 2, 3};
        String canonical = strategy.canonicalize(Json.mapper().getNodeFactory().binaryNode(bytes));

        assertThat(canonical)
                .isEqualTo(Json.mapper().getNodeFactory().binaryNode(bytes).toString());
    }

    @Test
    void parityFixtureMatchesJackson2DocumentedHash() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("z", 1);
        meta.put("a", 2);
        ParityPayload payload = new ParityPayload("o-parity", new BigDecimal("10.50"), List.of("a", "b"), meta);

        // Same canonical form and SHA-256 as idempotency-jackson2 for this POJO.
        assertThat(strategy.canonicalize(Json.valueToTree(payload)))
                .isEqualTo(
                        "{\"amount\":10.5,\"meta\":{\"a\":2,\"z\":1},\"orderId\":\"o-parity\",\"tags\":[\"a\",\"b\"]}");
        assertThat(strategy.calculate(payload))
                .isEqualTo("3422c4c5722f5437f6465687c0fe70b3481d1b3fa5594c4736443ca67cf2bce8");
    }
}
