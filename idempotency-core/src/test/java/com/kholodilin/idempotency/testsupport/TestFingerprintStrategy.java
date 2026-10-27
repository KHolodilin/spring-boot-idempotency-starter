package com.kholodilin.idempotency.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.kholodilin.idempotency.spi.FingerprintStrategy;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-only {@link FingerprintStrategy}: SHA-256 of Jackson JSON (no key sorting).
 * Sufficient for unit tests; production uses {@code CanonicalJsonFingerprintStrategy}.
 */
public final class TestFingerprintStrategy implements FingerprintStrategy {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public String calculate(@Nullable Object request) {
        try {
            String json = mapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
