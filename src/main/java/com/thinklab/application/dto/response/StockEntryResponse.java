package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** DTO for one line of a stock item's history. */
@Serdeable
public record StockEntryResponse(
        Instant occurredAt,
        String action,
        String executor,
        long quantity,
        long balanceAfter,
        String reason
) {}
