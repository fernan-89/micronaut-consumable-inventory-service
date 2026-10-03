package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.StockEntry;
import io.micronaut.core.annotation.Introspected;

import java.time.Instant;

/** Persistence form of a {@link StockEntry}, embedded in the stock item's document. */
@Introspected
public record StockEntryDocument(Instant occurredAt, String action, String executor, long quantity, long balanceAfter, String reason) {

    public static StockEntryDocument fromDomain(StockEntry entry) {
        return new StockEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.quantity(), entry.balanceAfter(), entry.reason());
    }

    public StockEntry toDomain() {
        return new StockEntry(occurredAt, action, executor, quantity, balanceAfter, reason);
    }
}
