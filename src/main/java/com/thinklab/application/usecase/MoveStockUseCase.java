package com.thinklab.application.usecase;

import com.thinklab.domain.exception.StockChangedException;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.repository.StockItemRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Moves stock (BIAN Behavior Qualifiers: {@code movement/receive}, {@code movement/issue}, {@code movement/adjust}), tenant-scoped.
 *
 * <p>The aggregate is loaded first and validates the move (ACTIVE, positive quantity, enough on hand for an issue), so a refused
 * move never writes. The write itself is conditional (ADR-031): an issue only applies if the item still holds enough, an
 * adjustment only if the quantity is still the one the caller saw. When a concurrent writer got in between, the answer is
 * {@link StockChangedException} (409, retry), never a lost update or a negative balance.
 */
@Singleton
public class MoveStockUseCase {

    private static final Logger log = LoggerFactory.getLogger(MoveStockUseCase.class);

    private final StockItemLookup stockItemLookup;
    private final StockItemRepository stockItemRepository;

    public MoveStockUseCase(StockItemLookup stockItemLookup, StockItemRepository stockItemRepository) {
        this.stockItemLookup = stockItemLookup;
        this.stockItemRepository = stockItemRepository;
    }

    public Mono<Void> receive(UUID id, UUID organisationId, long quantity, String reason, String executor) {
        log.info("[USE CASE] Receiving {} into stock item ID: {}", quantity, id);

        return stockItemLookup.owned(id, organisationId).flatMap(item -> {
            StockEntry entry = item.receive(quantity, reason, executor);
            return applied(item, stockItemRepository.applyDelta(id, entry.quantity(), entry));
        });
    }

    public Mono<Void> issue(UUID id, UUID organisationId, long quantity, String reason, String executor) {
        log.info("[USE CASE] Issuing {} from stock item ID: {}", quantity, id);

        return stockItemLookup.owned(id, organisationId).flatMap(item -> {
            StockEntry entry = item.issue(quantity, reason, executor);
            return applied(item, stockItemRepository.applyDelta(id, entry.quantity(), entry));
        });
    }

    public Mono<Void> adjust(UUID id, UUID organisationId, long newQuantity, String reason, String executor) {
        log.info("[USE CASE] Adjusting stock item ID: {} to {}", id, newQuantity);

        return stockItemLookup.owned(id, organisationId).flatMap(item -> {
            long expected = item.getOnHand();
            StockEntry entry = item.adjust(newQuantity, reason, executor);
            return applied(item, stockItemRepository.adjust(id, expected, newQuantity, entry));
        });
    }

    private static Mono<Void> applied(StockItem item, Mono<Boolean> write) {
        return write.flatMap(didApply -> Boolean.TRUE.equals(didApply)
                ? Mono.<Void>empty()
                : Mono.error(new StockChangedException(String.format(
                        "Stock item [%s] changed while this movement was being applied; nothing was written. Re-read it and retry.", item.getSku()))));
    }
}
