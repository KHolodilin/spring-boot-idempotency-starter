package com.kholodilin.idempotency.jackson;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import com.kholodilin.idempotency.spi.FingerprintStrategy;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.NumericNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Default {@link FingerprintStrategy}: canonical JSON + SHA-256.
 *
 * <p>Canonicalization rules guarantee a stable fingerprint for logically equal requests:
 * <ul>
 *   <li>object properties and map entries are sorted lexicographically by key;</li>
 *   <li>numbers are normalized via {@link BigDecimal#stripTrailingZeros()}
 *       ({@code 1.0} and {@code 1.00} produce the same fingerprint);</li>
 *   <li>no insignificant whitespace.</li>
 * </ul>
 */
public final class CanonicalJsonFingerprintStrategy implements FingerprintStrategy {

    private final String algorithm;

    public CanonicalJsonFingerprintStrategy() {
        this("SHA-256");
    }

    public CanonicalJsonFingerprintStrategy(String algorithm) {
        // fail fast on unknown algorithm
        try {
            MessageDigest.getInstance(algorithm);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unknown digest algorithm: " + algorithm, e);
        }
        this.algorithm = algorithm;
    }

    @Override
    public String calculate(@Nullable Object request) {
        String canonical = canonicalize(Json.valueToTree(request));
        return HexFormat.of().formatHex(digest().digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    String canonicalize(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(node, sb);
        return sb.toString();
    }

    private void writeCanonical(JsonNode node, StringBuilder sb) {
        switch (node) {
            case ObjectNode object -> {
                Map<String, JsonNode> sorted = new TreeMap<>();
                for (Map.Entry<String, JsonNode> property : object.properties()) {
                    sorted.put(property.getKey(), property.getValue());
                }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(entry.getKey(), sb);
                    sb.append(':');
                    writeCanonical(entry.getValue(), sb);
                }
                sb.append('}');
            }
            case ArrayNode array -> {
                sb.append('[');
                boolean first = true;
                for (JsonNode element : array) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeCanonical(element, sb);
                }
                sb.append(']');
            }
            case NumericNode number -> sb.append(normalize(number.numberValue()));
            case BooleanNode bool -> sb.append(bool.booleanValue());
            default -> {
                if (node.isNull()) {
                    sb.append("null");
                }
                else if (node.isString()) {
                    writeString(node.stringValue(), sb);
                }
                else {
                    // binary / POJO and any future node types: fall back to Jackson output
                    sb.append(node.toString());
                }
            }
        }
    }

    private static String normalize(Number number) {
        BigDecimal decimal = switch (number) {
            case BigDecimal d -> d;
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> new BigDecimal(f.toString());
            default -> new BigDecimal(number.toString());
        };
        return decimal.stripTrailingZeros().toPlainString();
    }

    private static void writeString(String value, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private MessageDigest digest() {
        try {
            return MessageDigest.getInstance(algorithm);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest algorithm disappeared: " + algorithm, e);
        }
    }
}
