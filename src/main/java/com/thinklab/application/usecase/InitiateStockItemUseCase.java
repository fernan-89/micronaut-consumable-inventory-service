package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateStockItemRequest;
import com.thinklab.application.dto.response.StockItemResponse;
import com.thinklab.application.mapper.StockItemMapper;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.StockItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Orchestrates StockItem creation (BIAN Behavior Qualifier: {@code initiate}): Sovereign ID, then the aggregate (which validates
 * every field), then the insert, whose unique {@code (organisationId, sku)} index refuses a duplicate SKU.
 */
@Singleton
public class InitiateStockItemUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateStockItemUseCase.class);

    private final HashServicePort hashServicePort;
    private final StockItemRepository stockItemRepository;

    public InitiateStockItemUseCase(HashServicePort hashServicePort, StockItemRepository stockItemRepository) {
        this.hashServicePort = hashServicePort;
        this.stockItemRepository = stockItemRepository;
    }

    public Mono<StockItemResponse> execute(UUID organisationId, InitiateStockItemRequest request, String executor) {
        log.info("[USE CASE] Initiating stock item {} for organisation: {}", request.sku(), organisationId);

        long initialQuantity = request.initialQuantity() == null ? 0L : request.initialQuantity();
        return hashServicePort.generateSovereignId("stock-item-creation")
                .map(id -> StockItem.createNew(id, organisationId, request.sku(), request.name(), request.unit(),
                        request.reorderLevel(), initialQuantity, executor))
                .flatMap(stockItemRepository::create)
                .map(StockItemMapper::toResponse);
    }
}
