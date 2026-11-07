package com.example.ratelimitdemo.web;

import java.util.UUID;

public record OrderResponse(UUID id, String item, int quantity) {
}
