# Spring Boot implementation (Java 21, Maven, Boot 4.1, Bucket4j 8.20)

Contents: package layout · dependencies · configuration · classes · usage on a controller · tests · what to verify first

Base package below is `com.example.ratelimitdemo`; replace it with the project's base package. All rate-limiting classes live in a `ratelimit` subpackage. None of them uses Jackson or mock beans, so they compile unchanged on Boot 3.x (only the web starter name and the test imports differ; see SKILL.md).

These sources were written against the documented Bucket4j 8.x and Spring Boot 4 APIs but must be compiled and tested in the target project: treat the first `mvn verify` as the real check, and consult the Bucket4j javadoc for the pinned version if a method name does not resolve.

## Package layout

```
src/main/java/com/example/ratelimitdemo/ratelimit/
    RateLimited.java
    KeyStrategy.java
    RateLimitProperties.java
    RateLimiterService.java
    RateLimitInterceptor.java
    RateLimitExceededException.java
    RateLimitExceptionHandler.java
    RateLimitConfig.java
    RateLimitWebConfig.java
src/main/resources/application.yml
src/test/java/com/example/ratelimitdemo/ratelimit/
    RateLimiterServiceTest.java
    OrdersRateLimitTest.java
```

## Dependencies

```xml
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j_jdk17-core</artifactId>
    <version>8.20.0</version>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
<!-- test scope, Boot 4: spring-boot-starter-test and spring-boot-starter-webmvc-test -->
```

## Configuration

```yaml
app:
  rate-limit:
    policies:
      orders:
        capacity: 10          # maximum burst
        refill-period: 1m     # refill-tokens omitted, so it equals capacity: 10 tokens per minute
        key: ip               # ip | user
      login:
        capacity: 5
        refill-period: 1m
        key: ip
      login-account:          # used programmatically from the service layer
        capacity: 5
        refill-period: 15m
```

Durations accept `30s`, `1m`, `15m`, `1h` or ISO-8601 (`PT1M`).

## Classes

### RateLimited.java

```java
package com.example.ratelimitdemo.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {

    /** Name of a policy defined under app.rate-limit.policies. */
    String policy();
}
```

### KeyStrategy.java

```java
package com.example.ratelimitdemo.ratelimit;

public enum KeyStrategy {
    /** Count per client IP address. */
    IP,
    /** Count per authenticated user; falls back to the IP address when unauthenticated. */
    USER
}
```

### RateLimitProperties.java

Validation happens in the record constructors, so a bad value stops the application at startup instead of failing on a request.

```java
package com.example.ratelimitdemo.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Map<String, Policy> policies) {

    public RateLimitProperties {
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    /**
     * @param capacity     maximum burst size (tokens in a full bucket)
     * @param refillTokens tokens added per refillPeriod; defaults to capacity
     * @param refillPeriod period over which refillTokens are added (continuous, "greedy" refill)
     * @param key          how requests are attributed to a client
     */
    public record Policy(int capacity, Integer refillTokens, Duration refillPeriod, KeyStrategy key) {

        public Policy {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be >= 1");
            }
            if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
                throw new IllegalArgumentException("refill-period must be a positive duration");
            }
            refillTokens = refillTokens == null ? Integer.valueOf(capacity) : refillTokens;
            if (refillTokens < 1) {
                throw new IllegalArgumentException("refill-tokens must be >= 1");
            }
            key = key == null ? KeyStrategy.IP : key;
        }

        /** Time needed to go from an empty bucket to a full one. */
        public Duration timeToRefillFully() {
            long periods = (capacity + refillTokens - 1L) / refillTokens;
            return refillPeriod.multipliedBy(periods);
        }
    }
}
```

### RateLimiterService.java

One Caffeine cache per policy, because the TTL depends on the policy. The TTL equals the time to refill fully: a bucket that was idle that long would be full anyway, so evicting it changes nothing.

```java
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

    /** Upper bound on tracked clients per policy; beyond it, least-recently-used buckets are evicted. */
    private static final long MAX_TRACKED_SUBJECTS = 100_000;

    private final Map<String, PolicyLimiter> limiters;

    public RateLimiterService(RateLimitProperties properties, TimeMeter timeMeter) {
        this.limiters = properties.policies().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> new PolicyLimiter(entry.getValue(), timeMeter)));
    }

    public RateLimitProperties.Policy policy(String name) {
        return limiter(name).policy;
    }

    /** Consumes one token for the subject under the named policy. */
    public Decision check(String policyName, String subject) {
        return limiter(policyName).consume(subject);
    }

    private PolicyLimiter limiter(String name) {
        PolicyLimiter limiter = limiters.get(name);
        if (limiter == null) {
            throw new IllegalStateException(
                    "Unknown rate-limit policy '" + name + "'. Define it under app.rate-limit.policies.");
        }
        return limiter;
    }

    private static long ceilSeconds(long nanos) {
        return (nanos + 999_999_999L) / 1_000_000_000L;
    }

    /**
     * @param limit             bucket capacity (burst size)
     * @param remaining         tokens left after this request
     * @param retryAfterSeconds 0 when allowed; otherwise seconds until a retry can succeed (rounded up, at least 1)
     * @param resetSeconds      seconds until the bucket is completely refilled (rounded up)
     */
    public record Decision(boolean allowed, long limit, long remaining, long retryAfterSeconds, long resetSeconds) {

        public Map<String, String> headers() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("RateLimit-Limit", Long.toString(limit));
            headers.put("RateLimit-Remaining", Long.toString(remaining));
            headers.put("RateLimit-Reset", Long.toString(resetSeconds));
            if (!allowed) {
                headers.put("Retry-After", Long.toString(retryAfterSeconds));
            }
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
            this.buckets = Caffeine.newBuilder()
                    .maximumSize(MAX_TRACKED_SUBJECTS)
                    .expireAfterAccess(policy.timeToRefillFully())
                    .build();
        }

        Decision consume(String subject) {
            Bucket bucket = buckets.get(subject, key -> newBucket());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            long retryAfter = probe.isConsumed()
                    ? 0
                    : Math.max(1, ceilSeconds(probe.getNanosToWaitForRefill()));
            return new Decision(
                    probe.isConsumed(),
                    policy.capacity(),
                    probe.getRemainingTokens(),
                    retryAfter,
                    ceilSeconds(probe.getNanosToWaitForReset()));
        }

        private Bucket newBucket() {
            Bandwidth limit = Bandwidth.builder()
                    .capacity(policy.capacity())
                    .refillGreedy(policy.refillTokens(), policy.refillPeriod())
                    .build();
            return Bucket.builder()
                    .withCustomTimePrecision(timeMeter)
                    .addLimit(limit)
                    .build();
        }
    }
}
```

### RateLimitInterceptor.java

```java
package com.example.ratelimitdemo.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiterService service;

    public RateLimitInterceptor(RateLimiterService service) {
        this.service = service;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true; // static resources, etc.
        }
        RateLimited annotation =
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RateLimited.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RateLimited.class);
        }
        if (annotation == null) {
            return true;
        }

        RateLimitProperties.Policy policy = service.policy(annotation.policy());
        RateLimiterService.Decision decision =
                service.check(annotation.policy(), subject(policy.key(), request));

        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision); // handled by RateLimitExceptionHandler
        }
        decision.headers().forEach(response::setHeader);
        return true;
    }

    private static String subject(KeyStrategy key, HttpServletRequest request) {
        if (key == KeyStrategy.USER && request.getUserPrincipal() != null) {
            return "user:" + request.getUserPrincipal().getName();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
```

### RateLimitExceededException.java

```java
package com.example.ratelimitdemo.ratelimit;

public class RateLimitExceededException extends RuntimeException {

    private final RateLimiterService.Decision decision;

    public RateLimitExceededException(RateLimiterService.Decision decision) {
        super("Rate limit exceeded", null, false, false); // no stack trace: this is flow control
        this.decision = decision;
    }

    public RateLimiterService.Decision decision() {
        return decision;
    }
}
```

### RateLimitExceptionHandler.java

Exceptions thrown from an interceptor's `preHandle` are resolved by the controller-advice mechanism, so this handler also covers the programmatic use in the service layer.

```java
package com.example.ratelimitdemo.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RateLimitExceptionHandler {

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> onRateLimitExceeded(RateLimitExceededException ex) {
        RateLimiterService.Decision decision = ex.decision();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Retry after " + decision.retryAfterSeconds() + " seconds.");
        body.setTitle("Too Many Requests");
        body.setProperty("retryAfterSeconds", decision.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers -> decision.headers().forEach(headers::set))
                .body(body);
    }
}
```

### RateLimitConfig.java and RateLimitWebConfig.java

Two classes on purpose: the `TimeMeter` bean must not live in the class that implements `WebMvcConfigurer`, because that class depends on the interceptor, which depends on the service, which needs the `TimeMeter`; one class would create a dependency cycle.

```java
package com.example.ratelimitdemo.ratelimit;

import io.github.bucket4j.TimeMeter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    TimeMeter rateLimitTimeMeter() {
        return TimeMeter.SYSTEM_NANOTIME;
    }
}
```

```java
package com.example.ratelimitdemo.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    public RateLimitWebConfig(RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
```

## Usage

On a controller method (the interceptor also accepts the annotation on a controller class, which limits every handler in it):

```java
@RateLimited(policy = "orders")
@PostMapping("/orders")
@ResponseStatus(HttpStatus.CREATED)
public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) {
    return orderService.create(request);
}
```

Programmatically, for a key that only exists in the body (per-account login limit). Normalize the key first, and return the same response whether or not the account exists:

```java
public LoginResponse login(LoginRequest request) {
    String account = request.email().strip().toLowerCase(Locale.ROOT);
    var decision = rateLimiterService.check("login-account", account);
    if (!decision.allowed()) {
        throw new RateLimitExceededException(decision);
    }
    // ...verify credentials...
}
```

## Tests

### RateLimiterServiceTest.java (unit; no Spring context)

```java
package com.example.ratelimitdemo.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimiterServiceTest {

    /** Manually advanced clock, so refill is tested without sleeping. */
    static final class ManualTimeMeter implements TimeMeter {
        private long nanos;

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }

    private static RateLimitProperties props(int capacity, int refillTokens, Duration period) {
        return new RateLimitProperties(
                Map.of("t", new RateLimitProperties.Policy(capacity, refillTokens, period, KeyStrategy.IP)));
    }

    @Test
    void rejectsAfterCapacityAndRefillsAfterPeriod() {
        var clock = new ManualTimeMeter();
        var service = new RateLimiterService(props(2, 2, Duration.ofMinutes(1)), clock);

        assertThat(service.check("t", "a").allowed()).isTrue();
        assertThat(service.check("t", "a").allowed()).isTrue();

        var denied = service.check("t", "a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L); // one token per 30 s

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
    void neverAllowsMoreThanCapacityUnderConcurrency() {
        var service = new RateLimiterService(props(5, 5, Duration.ofHours(1)), TimeMeter.SYSTEM_NANOTIME);
        var allowed = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                executor.submit(() -> {
                    if (service.check("t", "same").allowed()) {
                        allowed.incrementAndGet();
                    }
                });
            }
        } // close() waits for all tasks

        assertThat(allowed.get()).isEqualTo(5);
    }
}
```

### OrdersRateLimitTest.java (integration, MockMvc, Boot 4 imports)

Each test method uses its own IP because the Spring context, and therefore the buckets, is shared between test methods.

```java
package com.example.ratelimitdemo.ratelimit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.rate-limit.policies.orders.capacity=3")
class OrdersRateLimitTest {

    private static final String BODY = "{\"item\":\"book\",\"quantity\":1}";

    @Autowired
    private MockMvc mockMvc;

    private ResultActions postOrderFrom(String ip) throws Exception {
        return mockMvc.perform(post("/api/orders")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));
    }

    @Test
    void rejectsTheRequestAfterCapacityWith429() throws Exception {
        for (int i = 0; i < 3; i++) {
            postOrderFrom("203.0.113.10").andExpect(status().isCreated());
        }
        postOrderFrom("203.0.113.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void countsEachClientAddressSeparately() throws Exception {
        for (int i = 0; i < 3; i++) {
            postOrderFrom("203.0.113.20").andExpect(status().isCreated());
        }
        postOrderFrom("203.0.113.21").andExpect(status().isCreated());
    }

    @Test
    void doesNotLimitOtherEndpoints() throws Exception {
        for (int i = 0; i < 30; i++) {
            mockMvc.perform(get("/api/public/ping")).andExpect(status().isOk());
        }
    }
}
```

The test property overrides only `capacity`; because `refill-tokens` is omitted in `application.yml`, it follows the capacity (3 tokens per minute).

## What to verify first if the build fails

1. `TimeMeter`: if `isWallClockBased()` or `SYSTEM_NANOTIME` does not resolve, check the 8.20.0 javadoc for the `TimeMeter` interface.
2. `withCustomTimePrecision(...)`: on the local bucket builder in 8.x; confirm the name for the pinned version.
3. `getNanosToWaitForReset()` on `ConsumptionProbe`: confirm it exists and means "until the bucket is fully refilled".
4. Boot 4 test imports (`org.springframework.boot.webmvc.test.autoconfigure`) and the `spring-boot-starter-webmvc-test` dependency.
5. Caffeine version: if Maven reports a missing version, pin a current 3.x release.
