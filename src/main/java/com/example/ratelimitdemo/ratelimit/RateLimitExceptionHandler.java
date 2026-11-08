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
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Retry after " + decision.retryAfterSeconds() + " seconds.");
        body.setTitle("Too Many Requests");
        body.setProperty("retryAfterSeconds", decision.retryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(headers -> decision.headers().forEach(headers::set)).body(body);
    }
}
