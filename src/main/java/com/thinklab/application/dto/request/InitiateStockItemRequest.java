package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO for StockItem creation (BIAN Behavior Qualifier: {@code initiate}). organisationId travels via the {@code X-Tenant-Id}
 * header, not the body. {@code initialQuantity} is optional and defaults to 0.
 */
@Serdeable
public record InitiateStockItemRequest(

        @NotBlank(message = "SKU is required")
        @Size(max = 64, message = "SKU must not exceed 64 characters")
        String sku,

        @NotBlank(message = "Name is required")
        @Size(max = 160, message = "Name must not exceed 160 characters")
        String name,

        @NotBlank(message = "Unit is required")
        @Size(max = 20, message = "Unit must not exceed 20 characters")
        String unit,

        @NotNull(message = "Reorder level is required")
        @Min(value = 0, message = "Reorder level cannot be negative")
        Long reorderLevel,

        @Nullable
        @Min(value = 0, message = "Initial quantity cannot be negative")
        Long initialQuantity
) {}
