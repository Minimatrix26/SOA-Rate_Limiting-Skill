package com.example.ratelimitdemo.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RateLimiterService {
    private static final long MAX_TRACKED_SUBJECTS = 100_000;
    private final Map<String, PolicyLimiter> limiters;

    public RateLimiterService(RateLimitProperties properties, TimeMeter timeMeter) {
        limiters = properties.policies().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> new PolicyLimiter(entry.getValue(), timeMeter)));
    }

    public RateLimitProperties.Policy policy(String name) { return limiter(name).policy; }
    public Decision check(String policyName, String subject) { return limiter(policyName).consume(subject); }

    private PolicyLimiter limiter(String name) {
        PolicyLimiter limiter = limiters.get(name);
        if (limiter == null) throw new IllegalStateException("Unknown rate-limit policy '" + name + "'.");
        return limiter;
    }

    private static long ceilSeconds(long nanos) {
        return nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1);
    }

    public record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds, long resetSeconds) {
        public Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("RateLimit-Limit", Long.toString(limit));
            headers.put("RateLimit-Remaining", Long.toString(remaining));
            headers.put("RateLimit-Reset", Long.toString(resetSeconds));
            if (!allowed) headers.put("Retry-After", Long.toString(retryAfterSeconds));
            return headers;
        }
    }

    private static final class PolicyLimiter {
        private final RateLimitProperties.Policy policy;
        private final TimeMeter timeMeter;
        private final Cache<String, Bucket> buckets;

        PolicyLimiter(RateLimitProperties.Policy policy, TimeMeter timeMeter) {
            this.policy = policy;
            this.timeMeter = timeMeter;
            buckets = Caffeine.newBuilder().maximumSize(MAX_TRACKED_SUBJECTS)
                    .expireAfterAccess(policy.timeToRefillFully()).build();
        }

        Decision consume(String subject) {
            Bucket bucket = buckets.get(subject, key -> newBucket());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            long retryAfter = probe.isConsumed() ? 0 : Math.max(1, ceilSeconds(probe.getNanosToWaitForRefill()));
            return new Decision(probe.isConsumed(), policy.capacity(), probe.getRemainingTokens(), retryAfter,
                    ceilSeconds(probe.getNanosToWaitForReset()));
        }

        private Bucket newBucket() {
            Bandwidth limit = Bandwidth.builder().capacity(policy.capacity())
                    .refillGreedy(policy.refillTokens(), policy.refillPeriod()).build();
            return Bucket.builder().withCustomTimePrecision(timeMeter).addLimit(limit).build();
        }
    }
}
