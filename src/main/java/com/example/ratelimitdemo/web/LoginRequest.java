package com.example.ratelimitdemo.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Schema(example = "user@test.com") @NotBlank String email,
        @Schema(example = "Passw0rd!") @NotBlank String password) {
}
