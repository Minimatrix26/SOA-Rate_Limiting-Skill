package com.example.ratelimitdemo.ratelimit;

public class RateLimitExceededException extends RuntimeException {
    private final RateLimiterService.Decision decision;
    public RateLimitExceededException(RateLimiterService.Decision decision) {
        super("Rate limit exceeded", null, false, false);
        this.decision = decision;
    }
    public RateLimiterService.Decision decision() { return decision; }
}
