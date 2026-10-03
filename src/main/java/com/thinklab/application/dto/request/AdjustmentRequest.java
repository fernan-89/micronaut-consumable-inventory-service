package com.thinklab.application.dto.request;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** DTO for {@code movement/adjust}: the counted quantity, and why it differs. The reason is mandatory. */
@Serdeable
public record AdjustmentRequest(

        @NotNull(message = "Counted quantity is required")
        @Min(value = 0, message = "Counted quantity cannot be negative")
        Long newQuantity,

        @NotBlank(message = "A reason is required for an adjustment")
        @Size(max = 200, message = "Reason must not exceed 200 characters")
        String reason
) {}
