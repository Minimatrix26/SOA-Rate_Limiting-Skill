---
name: rate-limiting-spring-boot
description: Explain, design, and implement rate limiting in a Java 21 / Maven / Spring Boot (Spring MVC) REST API using Bucket4j (token bucket), a Caffeine cache of per-client buckets, a HandlerInterceptor driven by a @RateLimited annotation, and RFC 9457 ProblemDetail 429 responses with Retry-After (single-instance). Use this skill whenever the user wants to learn about rate limiting or throttling, wants ideas or options for limiting requests on Spring Boot endpoints, or asks to add, implement, or fix rate limiting, request quotas, 429 handling, or brute-force protection (login, API, orders, any endpoint) in a Spring Boot project, even if they only say "limit requests per minute", "stop abuse of this endpoint", or "throttle".
compatibility: Java 21, Maven 3.9+, Spring Boot 4.1.x (Spring MVC). Notes for adapting to Boot 3.x are included.
---

# Rate limiting in a Spring Boot REST API (Java 21, Maven)

Three modes: **Learn**, **Propose**, **Implement**. Choose the mode first (Step 0), then follow that mode's section. Detail lives in `references/`; read a file when the section says so.

- `references/algorithms.md`: how each algorithm works, trade-offs, mapping to Bucket4j terms
- `references/spring-boot-implementation.md`: complete code for every class, the configuration, and the tests
- `references/skeleton-app.md`: the baseline app (pinned versions) this skill is designed to be applied to

## Design at a glance

| Concern | Decision | Why |
|---|---|---|
| Algorithm | Token bucket via Bucket4j (`Bandwidth` with greedy refill) | Allows short bursts, exact integer arithmetic, lock-free, one small object per client |
| Where enforced | Spring MVC `HandlerInterceptor`, activated by `@RateLimited(policy = "...")` on controller methods | Knows the matched handler, so limits are per endpoint; runs after the Spring Security filters, so the authenticated user is available |
| Configuration | Named policies in `application.yml`, bound to a record | Numbers are visible and changeable without touching code |
| State | Caffeine cache of `subject -> Bucket`, with a size cap and a TTL | Bounded memory; correct for a single instance only (see pitfall 2) |
| Rejection | `429` + `Retry-After` + RFC 9457 `ProblemDetail` body, via `@RestControllerAdvice` | Standard and machine-readable |
| Headers | `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` on every limited response | Lets clients pace themselves |

Baseline versions (see `references/skeleton-app.md` for the reasoning): Java 21, Spring Boot **4.1.2**, `com.bucket4j:bucket4j_jdk17-core` **8.20.0**, Caffeine managed by the Boot BOM. If the project already uses other versions, keep them and adapt (see "Adapting to other Boot versions").

## Precision rules (apply in every mode)

1. **Every limit has five parts. State all five, with numbers.**
   - *Subject*: what is counted per (client IP, authenticated user, account identifier, or a combination)
   - *Limit*: bucket capacity (the maximum burst) and the sustained rate (`refill-tokens` per `refill-period`)
   - *Window / refill*: the refill period, e.g. 1 minute
   - *Scope*: which endpoint(s), identified by policy name and route
   - *Action on exceed*: reject with 429

   Never write "a reasonable limit". Write "capacity 10, refill 10 tokens per 1 m, per client IP, on `POST /api/orders`, reject with 429".
2. **In a token bucket, capacity and rate are different numbers.** Capacity 10 with refill 10 per minute allows a burst of 10 and then one request every 6 s on average. Always say which one you mean.
3. **Separate facts from assumptions.** Anything not given by the user or found in the code (traffic, proxy setup, number of instances) is an assumption, and must be labeled as one.
4. **Verify library APIs against the installed versions**, not memory (`pom.xml`, the effective Boot version, the Bucket4j javadoc or docs for the pinned version). Boot 4 moved many packages and starter names.
5. **Do not claim a behavior you have not verified.** "Should return 429" before testing, "returns 429" only after a test or request showed it.
6. **State the limitations of the design** that apply (see the pitfalls list).

## Step 0: Choose the mode

| Signal in the request | Mode | May edit files? |
|---|---|---|
| "what is", "explain", "how does", "difference between" | Learn | No |
| "how should I", "ideas", "options", "which algorithm", "what limit" | Propose | No |
| Explicit instruction to add / implement / apply / set up / fix limiting on a named or identifiable endpoint | Implement | Yes |

- If the request is ambiguous, do **not** change code. Answer in Propose mode and end by asking whether to implement.
- When the user moves from Propose to Implement, reuse every decision already made.
- Never modify project files in Learn or Propose mode.

## Mode 1: Learn

Scale to the question; a narrow question gets a narrow answer. Cover, in order:

1. **What and why.** Rate limiting caps how many requests a client may make in a period: abuse and brute-force prevention, fairness, protecting downstream capacity and cost, absorbing bursts.
2. **The five-part anatomy** from the precision rules.
3. **The algorithm relevant to the question.** Read `references/algorithms.md`, name the relevant algorithm, and give its key trade-off. Always connect the concept to Bucket4j vocabulary (capacity, `refillGreedy`, `refillIntervally`; mapping table in the reference).
4. **Where it can be enforced in a Spring app.** Edge (nginx, gateway): coarse and cheap. Servlet `Filter`: before Spring Security, so it can shield expensive filters, but it does not know the matched handler. `HandlerInterceptor`: after the filter chain, knows the handler and the user (this skill's choice). Service layer: for keys that only exist in the request body (e.g. the login email).
5. **The HTTP contract.** See "Response contract".
6. **Two or three relevant failure modes** from the pitfalls list.
7. **One worked example with numbers.** E.g. capacity 10, refill 10 per minute: a client may send 10 requests at once; the 11th is rejected with `Retry-After: 6`; after 6 s one more request is allowed; after 60 s idle the bucket is full again.

End with at most one follow-up question or offer.

## Mode 2: Propose

1. **Gather facts.** Inspect the project first when it is accessible; ask only what cannot be found:
   - Endpoint(s) and purpose (public read, login, expensive operation, webhook)
   - Client identity available (anonymous, session or JWT user, API key)
   - Spring MVC or WebFlux (this skill covers MVC only; for WebFlux, say so and propose a `WebFilter`-based design instead)
   - One instance or several (this skill's design is single-instance; if several, say so and see pitfall 2)
   - Behind a reverse proxy or load balancer (decides how the client IP is obtained)
   - Spring Security present or not, and the existing error response format
2. **Present two or three options.** For each: algorithm, subject key, exact policy numbers, storage, pros, and cons.
3. **Recommend one**, with reasoning tied to the gathered facts.
4. **List assumptions** and ask whether to implement.

### Options to choose from

| Option | Fits when | Notes |
|---|---|---|
| A. Bucket4j + Caffeine + interceptor (this skill's default) | One instance, or a demo | In-memory; counters are per JVM and reset on restart |
| B. Edge or gateway limit (nginx `limit_req`, API gateway, Spring Cloud Gateway `RequestRateLimiter`) | A gateway already exists, coarse per-IP limits are enough | Cannot see the authenticated user or per-endpoint business rules inside the app |
| C. Resilience4j `RateLimiter` | Limiting outbound calls to a dependency | Counts calls per refresh cycle for one named limiter; not designed for per-client keys |

If a Spring Boot starter for Bucket4j (declarative `application.yml` filters) is proposed, first verify that its current release supports the project's Boot version; do not assume it does.

### Starting-point policies

Use only when the user gave no numbers; present them as assumptions to adjust, never as correct answers. `refill-tokens` defaults to `capacity` when omitted.

| Policy name | Endpoint type | Subject | capacity | refill-period | Notes |
|---|---|---|---|---|---|
| `api` | Authenticated general API | user (falls back to IP) | 60 | 1m | Burst of 60, then 1 request/s |
| `public` | Unauthenticated read | IP | 20 | 1m | Beware shared IPs (NAT) |
| `login` | Login, password reset, OTP | IP | 5 | 1m | Plus a per-account policy (below) |
| `login-account` | Same endpoints | normalized account identifier | 5 | 15m | Checked in the service layer; stops distributed guessing. Keep responses identical whether or not the account exists |
| `orders` | Expensive or write endpoint | user (falls back to IP) | 10 | 1m | Consider a concurrency limit as well |

## Mode 3: Implement

Follow in order. Do not skip steps.

### 1. Inspect the project

Read `pom.xml` and note: Java version (Bucket4j `jdk17` artifacts need 17+; the baseline is 21); Boot version (parent or BOM import); web stack (`spring-boot-starter-webmvc` on Boot 4, `spring-boot-starter-web` on Boot 3.x). **If WebFlux is on the classpath instead of MVC, stop and tell the user this skill does not apply as written.** Also note: Spring Security presence, existing `@RestControllerAdvice` and error format, `application.yml` or `.properties`, test setup, and any `server.forward-headers-strategy` setting. Locate the target controller method and its route.

### 2. Fix the parameters

Use what the user specified. For anything missing, use the starting-point policies above and state each assumed value in one line before editing. Ask only if the target endpoint is unclear.

### 3. Add the dependencies

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
```

Caffeine's version comes from the Boot BOM. If Maven reports a missing version, run `mvn dependency:tree` and pin a current 3.x release.

### 4. Define the policies

```yaml
app:
  rate-limit:
    policies:
      orders:
        capacity: 10
        refill-period: 1m     # refill-tokens omitted: equals capacity
        key: ip
```

### 5. Add the classes

Copy the code from `references/spring-boot-implementation.md`, adjusting the package to the project's base package. One responsibility per class:

| Class | Responsibility |
|---|---|
| `RateLimited` | Annotation naming a policy; placed on a controller method or class |
| `KeyStrategy` | `IP` or `USER` |
| `RateLimitProperties` | Binds and validates the policies at startup (fails fast on bad numbers) |
| `RateLimiterService` | Holds one Caffeine cache of buckets per policy; returns a `Decision` |
| `RateLimitInterceptor` | Finds `@RateLimited`, derives the subject, calls the service, sets headers, throws on rejection |
| `RateLimitExceededException` + `RateLimitExceptionHandler` | Turn a rejection into 429 + `Retry-After` + `ProblemDetail` |
| `RateLimitConfig`, `RateLimitWebConfig` | Enable the properties and the `TimeMeter` bean; register the interceptor |

The core of the service is:

```java
Bandwidth limit = Bandwidth.builder()
        .capacity(policy.capacity())
        .refillGreedy(policy.refillTokens(), policy.refillPeriod())
        .build();
Bucket bucket = Bucket.builder().addLimit(limit).build();
ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
// probe.isConsumed(), probe.getRemainingTokens(), probe.getNanosToWaitForRefill()
```

### 6. Annotate the target endpoint only

```java
@RateLimited(policy = "orders")
@PostMapping("/orders")
public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) { ... }
```

Do not annotate other endpoints, and do not register a global limiter, unless the user asked for it.

### 7. Identify the client correctly

The interceptor uses `request.getRemoteAddr()`. Behind a reverse proxy this is the proxy's address, so all clients would share one bucket. If there is a proxy, configure Boot to resolve the real client address:

```yaml
server:
  forward-headers-strategy: native
  tomcat:
    remoteip:
      internal-proxies: "10\\.0\\.0\\.\\d{1,3}"   # regex matching YOUR proxies only
```

Never read `X-Forwarded-For` manually in the interceptor, and never trust it from clients that are not your proxy: a spoofed header lets a client pick a fresh bucket on every request. Without a proxy, leave the `server.*` settings alone.

For a `USER` key, the interceptor reads `request.getUserPrincipal()`, which Spring Security populates before interceptors run. Without authentication it falls back to the IP.

### 8. Handle keys that are not in the request line

The interceptor cannot read the request body without wrapping the request. For a limit keyed by something in the body (the login email), call the service from the service layer instead, with a normalized key:

```java
var decision = rateLimiterService.check("login-account", email.strip().toLowerCase(Locale.ROOT));
if (!decision.allowed()) throw new RateLimitExceededException(decision);
```

### 9. Add tests

Cover the checklist below with MockMvc plus a unit test of the service. Use a controllable `TimeMeter` for refill behavior (never `Thread.sleep`). Shared Spring contexts share bucket state across test methods, so give each test its own client IP.

On Boot 4, `@SpringBootTest` does not auto-configure `MockMvc`: add `@AutoConfigureMockMvc` (package `org.springframework.boot.webmvc.test.autoconfigure`) and the `spring-boot-starter-webmvc-test` test dependency. If an import does not resolve, check the compiler output against the Boot 4 packages rather than guessing Boot 3 ones.

Test checklist:
- Requests 1 to N succeed; request N+1 returns 429
- The 429 has `Retry-After` between 1 and the full refill time, and `RateLimit-Remaining: 0`
- The body is `application/problem+json` with `status` 429
- After the refill period (manual `TimeMeter`) requests succeed again
- Two different subjects do not share a bucket
- Under 200 concurrent calls with the same subject, exactly `capacity` are allowed
- Other endpoints are unaffected

### 10. Verify and report

Run `mvn -q verify` (or `./mvnw -q verify`). If the app can run locally, start it and send N+1 requests (see "Try it") and show the real status codes. Report: files changed; the five-part limit as implemented; algorithm and store; how the client is identified; the actual test and curl results; the limitations that apply; and what to change for production (a shared store if there are several instances, proxy configuration).

## Response contract

- **Status**: `429 Too Many Requests` (RFC 6585). Not 403 or 503.
- **`Retry-After`**: integer seconds, **rounded up** and at least 1. Truncating (for example with `TimeUnit.NANOSECONDS.toSeconds`) can yield `0` and invite an immediate retry.
- **Body**: RFC 9457 `ProblemDetail` (`application/problem+json`). If the project already has a global error format, follow it instead.
- **`RateLimit-Limit`**: the bucket capacity (the burst size), not the sustained rate. **`RateLimit-Remaining`**: tokens left. **`RateLimit-Reset`**: seconds until the bucket is completely refilled. These names follow an IETF draft whose syntax has changed between revisions; if the project already uses `X-RateLimit-*`, follow that instead. Send one scheme only, never both.
- Send the headers on successful responses too.

## Try it

```bash
# 12 quick requests against a policy with capacity 10, refill 10 per minute
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "%{http_code} " -X POST localhost:8080/api/orders \
       -H 'Content-Type: application/json' -d '{"item":"book","quantity":1}'
done; echo
# expected, if the loop finishes within a few seconds: 201 x10, then 429 429

# inspect the rejection
curl -i -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
     -d '{"item":"book","quantity":1}'
# HTTP/1.1 429, Retry-After: 6 (approximately), RateLimit-Remaining: 0, Content-Type: application/problem+json
```

## Rules and pitfalls

Check each against the design and report the ones that apply.

1. **Client IP behind a proxy.** Without `forward-headers-strategy` and the proxy list, every client shares the proxy's bucket. Trusting forwarded headers from anyone lets clients evade the limit.
2. **In-memory buckets are per JVM.** With N instances the effective limit is up to N times the configured one, and buckets reset on restart. Fine for a single-instance demo. This skill does not cover a shared store: if the deployment has several instances, say so explicitly and propose an edge or gateway limit, or a shared store as separate work.
3. **Caffeine TTL must be at least the full refill time.** An evicted bucket is recreated full. The TTL `capacity / refill-tokens * refill-period` makes eviction lossless. A shorter TTL silently grants extra tokens.
4. **The size cap is also a weakness.** With `maximumSize` reached (many distinct IPs), buckets are evicted early and those clients get fresh buckets. Mention it for public endpoints.
5. **Interceptors run after the Spring Security filter chain.** Expensive work done in filters (for example JWT parsing or password hashing inside a filter) is not shielded. Login logic in a controller or service is shielded.
6. **`@RateLimited` only works on controller handler methods** reached through Spring MVC, not on private methods or other beans. Use the service directly there.
7. **Unknown policy names.** An annotation naming a policy that is not defined fails with `IllegalStateException` at request time. A test that hits each limited endpoint catches this.
8. **Shared IPs.** Offices, universities, and mobile carriers put many users behind one address; IP-only limits on authenticated traffic penalize them. Prefer the `USER` key there.
9. **Retry storms.** Clients that retry instantly on 429 worsen overload. Where you control the client, use `Retry-After` plus random jitter.

## Adapting to other Boot versions

| Aspect | Boot 4.x (baseline) | Boot 3.x |
|---|---|---|
| Web starter | `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| Test starters | `spring-boot-starter-test` + `spring-boot-starter-webmvc-test` | `spring-boot-starter-test` |
| `@AutoConfigureMockMvc` / `@WebMvcTest` package | `org.springframework.boot.webmvc.test.autoconfigure` | `org.springframework.boot.test.autoconfigure.web.servlet` |
| Mock beans in tests | `@MockitoBean` (Spring Framework 7) | `@MockBean` |
| Rate limiting classes in this skill | unchanged | unchanged (no Jackson, no mock beans used) |

Bucket4j's `bucket4j_jdk17-core` needs Java 17+. For a project on Java 11, use the `bucket4j_jdk11-core` artifact and check its version.

## Short notes (mention, do not over-implement)

- **Per-plan limits** (free vs paid): add more policies and select the policy name from the user's plan in the service layer.
- **Two limits at once** (burst and sustained): add a second `Bandwidth` to the same bucket; both must be satisfied.
- **Concurrency limits** (max N requests in flight) are a different mechanism; mention them for slow or expensive endpoints.
- **Observability**: log rejections at `INFO` with the policy name and subject type, never the raw token or password. Counters per policy (Micrometer) are a natural extension.

## Checklist for the AI using this skill

- [ ] Mode chosen; no files changed in Learn or Propose mode
- [ ] Project inspected: Java, Boot version, MVC (not WebFlux), proxy, Security, error format
- [ ] Five-part limit stated with numbers; assumptions labeled
- [ ] `bucket4j_jdk17-core` and `caffeine` added; policies in `application.yml`
- [ ] Only the requested endpoint annotated
- [ ] 429 with `Retry-After` (rounded up, at least 1), `ProblemDetail` body, `RateLimit-*` headers
- [ ] Client IP handling correct for the deployment (proxy configured or not)
- [ ] Caffeine TTL at least the full refill time
- [ ] Tests cover the checklist; Boot 4 test imports and starters used
- [ ] Tests and a real request run; actual results reported; limitations listed
