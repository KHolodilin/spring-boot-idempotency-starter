package com.kholodilin.idempotency.autoconfigure;

import java.time.Duration;

import com.kholodilin.idempotency.jdbc.SchemaMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties of the idempotency starter, prefix {@code idempotency}.
 */
@Getter
@Setter
@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /**
     * Master switch for the whole idempotency auto-configuration.
     */
    private boolean enabled = true;

    private final Fingerprint fingerprint = new Fingerprint();

    private final LocalCacheSettings localCache = new LocalCacheSettings();

    private final DistributedCacheSettings distributedCache = new DistributedCacheSettings();

    private final Persistence persistence = new Persistence();

    @Getter
    @Setter
    public static class Fingerprint {

        /**
         * Digest algorithm of the default canonical-JSON fingerprint strategy.
         */
        private String algorithm = "SHA-256";
    }

    /**
     * Settings for the optional L1 local cache ({@code idempotency.local-cache.*}).
     * Named distinctly from the SPI {@code LocalCache} type.
     */
    @Getter
    @Setter
    public static class LocalCacheSettings {

        /**
         * Enable the Caffeine local cache (requires idempotency-local-cache-caffeine on
         * the classpath).
         */
        private boolean enabled = true;

        /**
         * Time-to-live of local cache entries.
         */
        private Duration ttl = Duration.ofMinutes(10);

        /**
         * Maximum number of local cache entries.
         */
        private long maxSize = 10_000;

        /**
         * Record Caffeine cache statistics.
         */
        private boolean statistics = false;
    }

    /**
     * Settings for the optional L2 distributed cache ({@code idempotency.distributed-cache.*}).
     * Named distinctly from the SPI {@code DistributedCache} type.
     */
    @Getter
    @Setter
    public static class DistributedCacheSettings {

        /**
         * Enable the Redis distributed cache (requires idempotency-distributed-cache-redis
         * on the classpath and a RedisConnectionFactory bean).
         */
        private boolean enabled = true;

        /**
         * Time-to-live of distributed cache entries.
         */
        private Duration ttl = Duration.ofHours(1);

        /**
         * Prefix of Redis keys.
         */
        private String keyPrefix = "idempotency:";

        /**
         * Behaviour when Redis is unavailable: fail-open (default, Redis failures are
         * treated as cache misses) or fail-fast. Kept as a plain string so this class
         * never references the optional Redis module.
         */
        private String failurePolicy = "fail-open";
    }

    @Getter
    @Setter
    public static class Persistence {

        /**
         * Enable the JDBC persistence store.
         */
        private boolean enabled = true;

        /**
         * Name of the idempotency table, optionally schema-qualified.
         */
        private String tableName = "idempotency_records";

        /**
         * Marker written to {@code expires_at} for physical cleanup. Does not affect
         * request-path visibility. Empty means the row is never cleaned up by TTL.
         */
        private Duration ttl = Duration.ofDays(365);

        /**
         * When {@code true}, persistence is queried before {@code acquire} on a cache
         * miss (better cold-duplicate latency). Default {@code false}: insert-first.
         */
        private boolean lookupBeforeAcquire = false;

        private final Schema schema = new Schema();

        private final Cleanup cleanup = new Cleanup();

        @Getter
        @Setter
        public static class Schema {

            /**
             * Schema management mode: create (execute canonical DDL at startup),
             * validate (fail fast if the table is missing or incompatible) or none.
             */
            private SchemaMode mode = SchemaMode.VALIDATE;
        }

        @Getter
        @Setter
        public static class Cleanup {

            /**
             * Enable the scheduled physical DELETE of expired persistence rows.
             */
            private boolean enabled = false;

            /**
             * Cron expression for the cleanup job (Spring 6-field format).
             */
            private String cron = "0 0 3 * * SAT,SUN";

            /**
             * Maximum rows deleted per batch iteration.
             */
            private int batchSize = 1000;
        }
    }
}
