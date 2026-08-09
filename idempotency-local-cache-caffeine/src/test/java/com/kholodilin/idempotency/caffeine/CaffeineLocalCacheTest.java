package com.kholodilin.idempotency.caffeine;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;
import com.kholodilin.idempotency.model.IdempotencyKey;
import com.kholodilin.idempotency.model.IdempotencyRecord;
import com.kholodilin.idempotency.model.IdempotencyStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineLocalCacheTest {

    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    private static IdempotencyRecord record(String key) {
        return IdempotencyRecord.processing(new IdempotencyKey("CREATE_PAYMENT", key), "hash", NOW, null)
                .completed("java.lang.String", "\"ok\"", NOW);
    }

    @Test
    void putGetEvictRoundTrip() {
        CaffeineLocalCache cache = new CaffeineLocalCache();
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "k-1");
        IdempotencyRecord record = record("k-1");

        assertThat(cache.get(key)).isEmpty();

        cache.put(key, record);
        assertThat(cache.get(key)).contains(record);
        assertThat(cache.get(key).get().status()).isEqualTo(IdempotencyStatus.COMPLETED);

        cache.evict(key);
        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void entriesExpireAfterTtl() {
        FakeTicker ticker = new FakeTicker();
        CaffeineLocalCache cache = new CaffeineLocalCache(Duration.ofMinutes(10), 100, false, ticker);
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "k-ttl");

        cache.put(key, record("k-ttl"));
        assertThat(cache.get(key)).isPresent();

        ticker.advance(Duration.ofMinutes(9));
        assertThat(cache.get(key)).as("still within TTL").isPresent();

        ticker.advance(Duration.ofMinutes(2));
        assertThat(cache.get(key)).as("expired after TTL").isEmpty();
    }

    @Test
    void sizeIsBounded() {
        CaffeineLocalCache cache = new CaffeineLocalCache(Duration.ofMinutes(10), 10, false, null);

        for (int i = 0; i < 1000; i++) {
            cache.put(new IdempotencyKey("CREATE_PAYMENT", "k-" + i), record("k-" + i));
        }

        assertThat(cache.estimatedSize()).isLessThanOrEqualTo(10);
    }

    @Test
    void statisticsAreRecordedWhenEnabled() {
        CaffeineLocalCache cache = new CaffeineLocalCache(Duration.ofMinutes(10), 100, true, null);
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "k-stats");

        cache.get(key); // miss
        cache.put(key, record("k-stats"));
        cache.get(key); // hit

        assertThat(cache.statistics().hitCount()).isEqualTo(1);
        assertThat(cache.statistics().missCount()).isEqualTo(1);
    }

    @Test
    void statisticsAreZeroWhenDisabled() {
        CaffeineLocalCache cache = new CaffeineLocalCache(Duration.ofMinutes(10), 100, false, null);
        IdempotencyKey key = new IdempotencyKey("CREATE_PAYMENT", "k-nostats");

        cache.get(key);
        cache.put(key, record("k-nostats"));
        cache.get(key);

        assertThat(cache.statistics().hitCount()).isZero();
        assertThat(cache.statistics().missCount()).isZero();
    }

    private static final class FakeTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
