package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/** DTO for StockItem output payload. {@code belowReorderLevel} is true when the quantity on hand is at or under the reorder level. */
@Serdeable
public record StockItemResponse(
        UUID id,
        UUID organisationId,
        String sku,
        String name,
        String unit,
        long onHand,
        long reorderLevel,
        boolean belowReorderLevel,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
