package com.example.ratelimitdemo.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterServiceTest {
    static final class ManualTimeMeter implements TimeMeter {
        private long nanos;
        @Override public long currentTimeNanos() { return nanos; }
        @Override public boolean isWallClockBased() { return false; }
        void advance(Duration duration) { nanos += duration.toNanos(); }
    }

    private static RateLimitProperties props(int capacity, int tokens, Duration period) {
        return new RateLimitProperties(Map.of("t", new RateLimitProperties.Policy(capacity, tokens, period, KeyStrategy.IP)));
    }

    @Test
    void rejectsAfterCapacityAndRefillsWithManualClock() {
        var clock = new ManualTimeMeter();
        var service = new RateLimiterService(props(2, 2, Duration.ofMinutes(1)), clock);
        assertThat(service.check("t", "a").allowed()).isTrue();
        assertThat(service.check("t", "a").allowed()).isTrue();
        var denied = service.check("t", "a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
        clock.advance(Duration.ofMinutes(1));
        assertThat(service.check("t", "a").allowed()).isTrue();
    }

    @Test
    void subjectsHaveIndependentBuckets() {
        var service = new RateLimiterService(props(1, 1, Duration.ofHours(1)), TimeMeter.SYSTEM_NANOTIME);
        assertThat(service.check("t", "a").allowed()).isTrue();
        assertThat(service.check("t", "a").allowed()).isFalse();
        assertThat(service.check("t", "b").allowed()).isTrue();
    }

    @Test
    void onlyCapacityCallsAllowedUnder200ConcurrentCalls() {
        var service = new RateLimiterService(props(5, 5, Duration.ofHours(1)), TimeMeter.SYSTEM_NANOTIME);
        var allowed = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) executor.submit(() -> {
                if (service.check("t", "same").allowed()) allowed.incrementAndGet();
            });
        }
        assertThat(allowed.get()).isEqualTo(5);
    }
}
