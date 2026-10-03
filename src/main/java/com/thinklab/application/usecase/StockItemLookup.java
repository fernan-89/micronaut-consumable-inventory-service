package com.thinklab.application.usecase;

import com.thinklab.domain.exception.StockItemNotFoundException;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.repository.StockItemRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Loads a stock item on behalf of one tenant. Another tenant's item answers exactly like a missing one (404), so a valid id
 * never reveals that it exists elsewhere.
 */
@Singleton
public class StockItemLookup {

    private final StockItemRepository stockItemRepository;

    public StockItemLookup(StockItemRepository stockItemRepository) {
        this.stockItemRepository = stockItemRepository;
    }

    public Mono<StockItem> owned(UUID id, UUID organisationId) {
        return stockItemRepository.findById(id)
                .filter(item -> item.getOrganisationId().equals(organisationId))
                .switchIfEmpty(Mono.error(new StockItemNotFoundException(id)));
    }
}
