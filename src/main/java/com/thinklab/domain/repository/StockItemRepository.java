package com.thinklab.domain.repository;

import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.model.StockItem.StockStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Outbound Port for StockItem persistence (Consumable Inventory Service Domain). Every change is one atomic update that also
 * appends its {@link StockEntry} to the item's history (ADR-002); there is no physical delete.
 *
 * <p>The two quantity operations answer {@code false} instead of failing when the guard did not hold at write time: the use case
 * validated against a loaded copy, and a concurrent writer changed the item in between (ADR-031).
 */
public interface StockItemRepository {

    /** Fails with {@code DuplicateStockItemException} when the organisation already has this SKU. */
    Mono<StockItem> create(StockItem item);

    Mono<StockItem> findById(UUID id);

    /** The organisation's items by SKU, optionally only one status and/or one exact SKU. */
    Flux<StockItem> findAll(UUID organisationId, StockStatus status, String sku);

    /** ACTIVE items whose quantity on hand is at or below their reorder level. */
    Flux<StockItem> findLowStock(UUID organisationId);

    /**
     * Adds {@code delta} (negative to issue) to the quantity on hand and appends the entry, only if the item is ACTIVE and, for a
     * negative delta, still holds enough. {@code true} when it applied.
     */
    Mono<Boolean> applyDelta(UUID id, long delta, StockEntry entry);

    /** Sets the quantity on hand, only if the item is ACTIVE and still holds {@code expectedOnHand}. {@code true} when it applied. */
    Mono<Boolean> adjust(UUID id, long expectedOnHand, long newQuantity, StockEntry entry);

    Mono<Void> updateDetails(UUID id, String name, String unit, long reorderLevel, StockEntry entry);

    Mono<Void> updateStatus(UUID id, StockStatus status, StockEntry entry);
}
