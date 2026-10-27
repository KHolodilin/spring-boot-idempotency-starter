package com.kholodilin.idempotency.reactive.autoconfigure;

import com.kholodilin.idempotency.jackson2.CanonicalJsonFingerprintStrategy;
import com.kholodilin.idempotency.jackson2.Jackson2IdempotencySerializer;
import com.kholodilin.idempotency.spi.FingerprintStrategy;
import com.kholodilin.idempotency.spi.IdempotencySerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Boot 3 / Jackson 2 defaults for {@link FingerprintStrategy} and
 * {@link IdempotencySerializer} ({@link CanonicalJsonFingerprintStrategy},
 * {@link Jackson2IdempotencySerializer}). Registered early so Redis and the core
 * service can both depend on them; override either bean with
 * {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyJacksonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FingerprintStrategy idempotencyFingerprintStrategy(IdempotencyProperties properties) {
        return new CanonicalJsonFingerprintStrategy(properties.getFingerprint().getAlgorithm());
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotencySerializer idempotencySerializer() {
        return new Jackson2IdempotencySerializer();
    }
}
