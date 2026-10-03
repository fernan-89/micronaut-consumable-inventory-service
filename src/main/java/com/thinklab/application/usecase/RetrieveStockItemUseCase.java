package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.StockEntryResponse;
import com.thinklab.application.dto.response.StockItemResponse;
import com.thinklab.application.mapper.StockItemMapper;
import com.thinklab.domain.model.StockItem.StockStatus;
import com.thinklab.domain.repository.StockItemRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Read side of the StockItem aggregate (BIAN Behavior Qualifier: {@code retrieve}), always scoped to one tenant. */
@Singleton
public class RetrieveStockItemUseCase {

    private final StockItemLookup stockItemLookup;
    private final StockItemRepository stockItemRepository;

    public RetrieveStockItemUseCase(StockItemLookup stockItemLookup, StockItemRepository stockItemRepository) {
        this.stockItemLookup = stockItemLookup;
        this.stockItemRepository = stockItemRepository;
    }

    public Mono<StockItemResponse> byId(UUID id, UUID organisationId) {
        return stockItemLookup.owned(id, organisationId).map(StockItemMapper::toResponse);
    }

    public Flux<StockItemResponse> all(UUID organisationId, StockStatus status, String sku) {
        return stockItemRepository.findAll(organisationId, status, sku).map(StockItemMapper::toResponse);
    }

    public Flux<StockItemResponse> lowStock(UUID organisationId) {
        return stockItemRepository.findLowStock(organisationId).map(StockItemMapper::toResponse);
    }

    /** The item's recent history, newest first. */
    public Mono<List<StockEntryResponse>> history(UUID id, UUID organisationId) {
        return stockItemLookup.owned(id, organisationId)
                .map(item -> item.getHistory().reversed().stream().map(StockItemMapper::toResponse).toList());
    }
}
