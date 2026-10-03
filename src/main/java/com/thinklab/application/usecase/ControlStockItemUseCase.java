package com.thinklab.application.usecase;

import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem.StockStatus;
import com.thinklab.domain.repository.StockItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Governs the StockItem lifecycle (BIAN Behavior Qualifier: {@code control/discontinue}), tenant-scoped. The aggregate refuses an
 * illegal move (409) before the granular update is issued with its history entry.
 */
@Singleton
public class ControlStockItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlStockItemUseCase.class);

    private final StockItemLookup stockItemLookup;
    private final StockItemRepository stockItemRepository;

    public ControlStockItemUseCase(StockItemLookup stockItemLookup, StockItemRepository stockItemRepository) {
        this.stockItemLookup = stockItemLookup;
        this.stockItemRepository = stockItemRepository;
    }

    public Mono<Void> discontinue(UUID id, UUID organisationId, String executor) {
        log.info("[USE CASE] Discontinuing stock item ID: {}", id);

        return stockItemLookup.owned(id, organisationId).flatMap(item -> {
            StockEntry entry = item.discontinue(executor);
            return stockItemRepository.updateStatus(id, StockStatus.DISCONTINUED, entry);
        });
    }
}
