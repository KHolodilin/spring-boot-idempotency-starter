package com.kholodilin.idempotency.autoconfigure;

import java.time.Duration;

import com.kholodilin.idempotency.jdbc.SchemaMode;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties of the idempotency starter, prefix {@code idempotency}.
 */
@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /**
     * Master switch for the whole idempotency auto-configuration.
     */
    private boolean enabled = true;

    private final Fingerprint fingerprint = new Fingerprint();

    private final LocalCache localCache = new LocalCache();

    private final DistributedCache distributedCache = new DistributedCache();

    private final Persistence persistence = new Persistence();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Fingerprint getFingerprint() {
        return fingerprint;
    }

    public LocalCache getLocalCache() {
        return localCache;
    }

    public DistributedCache getDistributedCache() {
        return distributedCache;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public static class Fingerprint {

        /**
         * Digest algorithm of the default canonical-JSON fingerprint strategy.
         */
        private String algorithm = "SHA-256";

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }
    }

    public static class LocalCache {

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public long getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(long maxSize) {
            this.maxSize = maxSize;
        }

        public boolean isStatistics() {
            return statistics;
        }

        public void setStatistics(boolean statistics) {
            this.statistics = statistics;
        }
    }

    public static class DistributedCache {

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public String getFailurePolicy() {
            return failurePolicy;
        }

        public void setFailurePolicy(String failurePolicy) {
            this.failurePolicy = failurePolicy;
        }
    }

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
         * How long a stored outcome stays replayable. Empty means no expiration.
         */
        private Duration ttl = Duration.ofHours(24);

        private final Schema schema = new Schema();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Schema getSchema() {
            return schema;
        }

        public static class Schema {

            /**
             * Schema management mode: create (execute canonical DDL at startup),
             * validate (fail fast if the table is missing or incompatible) or none.
             */
            private SchemaMode mode = SchemaMode.VALIDATE;

            public SchemaMode getMode() {
                return mode;
            }

            public void setMode(SchemaMode mode) {
                this.mode = mode;
            }
        }
    }
}
