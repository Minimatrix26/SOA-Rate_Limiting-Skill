# Rate Limiting Algorithms

Contents: comparison table · fixed window · sliding window log · sliding window counter · token bucket · leaky bucket / GCRA · concurrency limit · mapping to Bucket4j

## Comparison

| Algorithm | State per key | Allows bursts | Accuracy | Main weakness |
|---|---|---|---|---|
| Fixed window | 1 counter + expiry | Up to 2x limit across a boundary | Low at boundaries | Boundary burst |
| Sliding window log | 1 timestamp per request | No | Exact | Memory grows with the limit |
| Sliding window counter | 2 counters | Slightly | Approximate (assumes even spread in the previous window) | Approximation error |
| Token bucket | tokens + last-refill time | Yes, up to capacity | Exact for its definition | Two parameters to tune |
| Leaky bucket (meter) / GCRA | 1 timestamp | Configurable | Exact | Less intuitive |
| Concurrency limit | 1 in-flight counter | n/a | Exact | Needs reliable release on every exit path |

## Fixed window

Time is divided into windows of length W (e.g. 60 s). Each key has a counter reset at the window boundary. Request allowed if `count <= limit`.

- Cheapest and simplest: `INCR key`, set expiry on the first increment.
- Boundary burst: a client sending `limit` requests at the end of window k and `limit` at the start of window k+1 gets `2 x limit` through in a span much shorter than W.
- Retry-After = seconds until the current window ends.
- Good for: demos, coarse protection, cases where boundary bursts are harmless. Many framework built-ins (e.g. Laravel's `Limit::perMinute`) behave this way.

## Sliding window log

Store a timestamp for each accepted request (e.g. a Redis sorted set scored by time). On each request: remove entries older than `now - W`, count the rest, allow if `count < limit`, then add the new timestamp.

- Exact: no boundary burst.
- Memory is O(limit) per key; costly for high limits or many keys.
- Retry-After = `oldest_timestamp + W - now`.

## Sliding window counter

Keep the counts for the current and previous fixed windows. Estimate the count in the sliding window as:

```
estimated = previous_count * (1 - elapsed / W) + current_count
```

where `elapsed` is the time since the current window started. Allow if `estimated < limit`.

- Two counters per key; smooths the boundary burst.
- Approximate: assumes the previous window's requests were evenly spread.
- Good default when exactness is not required and memory matters.

## Token bucket

A bucket holds up to `capacity` tokens and refills at `rate` tokens per second. Each request costs `cost` tokens (usually 1). Allowed if enough tokens remain.

```
tokens = min(capacity, tokens + (now - last) * rate)
if tokens >= cost: tokens -= cost; allow
else: deny; retry_after = (cost - tokens) / rate
last = now
```

- `capacity` sets the maximum burst; `rate` sets the sustained throughput.
- Example: capacity 10, rate 1/s allows 10 instant requests, then 1 per second. An idle client regains a full burst after 10 s.
- Supports weighted costs (e.g. an export costs 5).
- Good default for user-facing APIs where short bursts are normal.
- Key TTL should be at least `capacity / rate` (time to refill fully); after that the key can be dropped because a missing key means a full bucket.

## Leaky bucket and GCRA

- **As a queue**: requests enter a bounded queue that drains at a constant rate; overflow is rejected. Output is smooth, so excess requests are delayed rather than rejected. (nginx `limit_req` without `nodelay` behaves this way.)
- **As a meter**: equivalent to a token bucket where "bucket full" means "no capacity left". The Generic Cell Rate Algorithm (GCRA) implements this with a single stored value, the theoretical arrival time (TAT). It is exact, stores one timestamp per key, and underlies several popular Redis rate-limit libraries.

## Concurrency limit

Caps requests in flight at once rather than requests per time. Increment on entry, decrement in a `finally` (or equivalent) so the count is released on every exit path including errors and timeouts. Protect against leaks with a TTL on the counter. Suited to expensive or long-running operations, and often used alongside a rate limit.

## Mapping to Bucket4j

Bucket4j implements the token bucket. Its vocabulary maps to the algorithm like this:

| Token bucket term | Bucket4j (8.x) | Notes |
|---|---|---|
| capacity (maximum burst) | `Bandwidth.builder().capacity(n)` | The bucket never holds more than `n` tokens |
| refill rate | `.refillGreedy(tokens, period)` | Adds tokens continuously: `tokens/period` per unit time |
| refill, all at once | `.refillIntervally(tokens, period)` | Adds all `tokens` at the end of each period (fixed-window-like behavior, boundary bursts possible) |
| cost per request | `bucket.tryConsume(cost)` | Use `cost > 1` for expensive operations |
| remaining tokens, wait time | `ConsumptionProbe` from `tryConsumeAndReturnRemaining` | `getRemainingTokens()`, `getNanosToWaitForRefill()` (until a rejected request could succeed), `getNanosToWaitForReset()` (until the bucket is full) |
| two limits at once | `.addLimit(a).addLimit(b)` on the builder | Every limit must allow the request, e.g. burst 20 per second and sustained 100 per minute |

Worked numbers for `capacity(10).refillGreedy(10, Duration.ofMinutes(1))`: one token every 6 s; ten instant requests are allowed; the 11th is rejected with a wait of about 6 s; after 60 s without requests the bucket is full again.
