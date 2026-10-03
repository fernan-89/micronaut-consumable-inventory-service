package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateStockItemRequest;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.repository.StockItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Replaces a stock item's name, unit and reorder level (BIAN Behavior Qualifier: {@code update}), tenant-scoped. */
@Singleton
public class UpdateStockItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateStockItemUseCase.class);

    private final StockItemLookup stockItemLookup;
    private final StockItemRepository stockItemRepository;

    public UpdateStockItemUseCase(StockItemLookup stockItemLookup, StockItemRepository stockItemRepository) {
        this.stockItemLookup = stockItemLookup;
        this.stockItemRepository = stockItemRepository;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdateStockItemRequest request, String executor) {
        log.info("[USE CASE] Updating stock item ID: {}", id);

        return stockItemLookup.owned(id, organisationId).flatMap(item -> {
            StockEntry entry = item.updateDetails(request.name(), request.unit(), request.reorderLevel(), executor);
            return stockItemRepository.updateDetails(id, item.getName(), item.getUnit(), item.getReorderLevel(), entry);
        });
    }
}
