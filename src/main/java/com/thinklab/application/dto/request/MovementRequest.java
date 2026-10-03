package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for {@code movement/receive} and {@code movement/issue}: how many, and an optional note on why. The note is free text that
 * lands in the item's history: it must not carry personal data.
 */
@Serdeable
public record MovementRequest(

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be a positive number")
        Long quantity,

        @Nullable
        @Size(max = 200, message = "Reason must not exceed 200 characters")
        String reason
) {}
