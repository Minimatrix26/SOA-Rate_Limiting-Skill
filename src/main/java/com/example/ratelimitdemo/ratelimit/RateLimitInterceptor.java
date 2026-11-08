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
    public RateLimitInterceptor(RateLimiterService service) { this.service = service; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        RateLimited annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RateLimited.class);
        if (annotation == null) annotation = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimited.class);
        if (annotation == null) return true;
        RateLimitProperties.Policy policy = service.policy(annotation.policy());
        RateLimiterService.Decision decision = service.check(annotation.policy(), subject(policy.key(), request));
        if (!decision.allowed()) throw new RateLimitExceededException(decision);
        decision.headers().forEach(response::setHeader);
        return true;
    }

    private static String subject(KeyStrategy key, HttpServletRequest request) {
        if (key == KeyStrategy.USER && request.getUserPrincipal() != null) return "user:" + request.getUserPrincipal().getName();
        return "ip:" + request.getRemoteAddr();
    }
}
