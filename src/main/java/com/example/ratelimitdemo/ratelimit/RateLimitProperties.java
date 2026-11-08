package com.example.ratelimitdemo.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Map<String, Policy> policies) {
    public RateLimitProperties {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    public record Policy(int capacity, Integer refillTokens, Duration refillPeriod, KeyStrategy key) {
        public Policy {
            if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
            if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
                throw new IllegalArgumentException("refill-period must be a positive duration");
            }
            refillTokens = refillTokens == null ? capacity : refillTokens;
            if (refillTokens < 1) throw new IllegalArgumentException("refill-tokens must be >= 1");
            key = key == null ? KeyStrategy.IP : key;
        }

        public Duration timeToRefillFully() {
            long periods = (capacity + refillTokens - 1L) / refillTokens;
            return refillPeriod.multipliedBy(periods);
        }
    }
}
