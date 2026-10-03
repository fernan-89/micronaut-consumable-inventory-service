package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.StockEntryResponse;
import com.thinklab.application.dto.response.StockItemResponse;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;

/** Static factory mapper for StockItem DTOs and Domain Entities. Enforces the DTO Isolation Pattern. */
public final class StockItemMapper {

    private StockItemMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static StockItemResponse toResponse(StockItem item) {
        return new StockItemResponse(item.getId(), item.getOrganisationId(), item.getSku(), item.getName(), item.getUnit(),
                item.getOnHand(), item.getReorderLevel(), item.isBelowReorderLevel(), item.getStatus().name(),
                item.getCreatedAt(), item.getUpdatedAt());
    }

    public static StockEntryResponse toResponse(StockEntry entry) {
        return new StockEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.quantity(), entry.balanceAfter(), entry.reason());
    }
}
