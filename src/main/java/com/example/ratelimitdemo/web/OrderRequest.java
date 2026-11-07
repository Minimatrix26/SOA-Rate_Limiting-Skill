package com.example.ratelimitdemo.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record OrderRequest(
        @Schema(example = "book") @NotBlank String item,
        @Schema(example = "1") @Min(1) int quantity) {
}
