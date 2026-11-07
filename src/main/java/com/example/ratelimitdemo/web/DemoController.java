package com.example.ratelimitdemo.web;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class DemoController {

    private static final String DEMO_EMAIL = "user@test.com";
    private static final String DEMO_PASSWORD = "Passw0rd!";

    @GetMapping("/public/ping")
    @Operation(summary = "Check that the public API is available")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an order")
    public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) {
        return new OrderResponse(UUID.randomUUID(), request.item(), request.quantity());
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Log in with the demo credentials")
    public Map<String, String> login(@Valid @RequestBody LoginRequest request) {
        if (!DEMO_EMAIL.equals(request.email()) || !DEMO_PASSWORD.equals(request.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return Map.of("token", "demo-token");
    }
}
