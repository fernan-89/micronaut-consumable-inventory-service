package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.dto.request.InitiateStockItemRequest;
import com.thinklab.application.dto.response.StockItemResponse;
import com.thinklab.application.usecase.ControlStockItemUseCase;
import com.thinklab.application.usecase.InitiateStockItemUseCase;
import com.thinklab.application.usecase.MoveStockUseCase;
import com.thinklab.application.usecase.RetrieveStockItemUseCase;
import com.thinklab.domain.exception.DuplicateStockItemException;
import com.thinklab.domain.exception.InsufficientStockException;
import com.thinklab.domain.exception.StockChangedException;
import com.thinklab.domain.exception.StockItemNotFoundException;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.StockItemRepository;
import com.thinklab.infrastructure.adapter.out.integration.hashservice.HashServiceAdapter;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stock against a real MongoDB, proving what no mock can: the conditional update really keeps the balance from going below zero
 * under concurrent issues, the unique SKU index really refuses a duplicate, a counted adjustment really only applies to the
 * quantity the caller saw, the history really is sliced to its limit, and the low-stock expression filter really compares two
 * fields of the same document (ADR-031).
 *
 * <p>{@code packages = "com.thinklab"}: otherwise Micronaut Data MongoDB stops mapping {@code @Id} to {@code _id} for entities
 * outside the test's own package.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_consumable_inventory_it";
    private static final String EXECUTOR = "stock-clerk";

    /** The hash service is another process; the inventory only needs a fresh UUID from it. */
    @Singleton
    @Replaces(HashServiceAdapter.class)
    static class FixedHashService implements HashServicePort {
        @Override
        public Mono<UUID> generateSovereignId(String purpose) {
            return Mono.fromSupplier(UUID::randomUUID);
        }

        @Override
        public Mono<String> hashSensitiveData(String rawData) {
            return Mono.just(rawData);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject InitiateStockItemUseCase initiate;
    @Inject MoveStockUseCase move;
    @Inject ControlStockItemUseCase control;
    @Inject RetrieveStockItemUseCase retrieve;
    @Inject StockItemRepository repository;

    private StockItemResponse register(UUID org, String sku, long reorderLevel, long quantity) {
        return initiate.execute(org, new InitiateStockItemRequest(sku, "Item " + sku, "unit", reorderLevel, quantity), EXECUTOR).block();
    }

    @Test
    @DisplayName("an item round-trips through MongoDB with its history, and a duplicate SKU is refused by the unique index, also concurrently")
    void roundTripAndUniqueSku() {
        UUID org = UUID.randomUUID();
        StockItemResponse created = register(org, "toner", 2, 10);
        move.issue(created.id(), org, 4, "job 1", EXECUTOR).block();

        StockItemResponse stored = retrieve.byId(created.id(), org).block();
        assertEquals(6, stored.onHand());
        assertEquals("TONER", stored.sku());
        var history = retrieve.history(created.id(), org).block();
        assertEquals(List.of("ISSUED", "INITIATED"), history.stream().map(h -> h.action()).toList());
        assertEquals(-4, history.get(0).quantity());
        assertEquals(6, history.get(0).balanceAfter());

        assertThrows(DuplicateStockItemException.class, () -> register(org, "TONER", 1, 1));
        register(UUID.randomUUID(), "TONER", 1, 1);

        UUID racing = UUID.randomUUID();
        List<Object> outcomes = Flux.range(0, 6)
                .flatMap(i -> initiate.execute(racing, new InitiateStockItemRequest("CABLE", "Cable", "unit", 1L, 0L), EXECUTOR).cast(Object.class)
                        .onErrorResume(e -> Mono.just(e)), 6)
                .collectList().block();
        assertEquals(1, outcomes.stream().filter(o -> o instanceof StockItemResponse).count(), outcomes.toString());
        assertEquals(5, outcomes.stream().filter(o -> o instanceof DuplicateStockItemException).count(), outcomes.toString());
    }

    @Test
    @DisplayName("concurrent issues never take the balance below zero: exactly the available units are issued, the rest are refused")
    void concurrentIssues() {
        UUID org = UUID.randomUUID();
        StockItemResponse item = register(org, "SSD", 1, 5);

        List<Object> outcomes = Flux.range(0, 12)
                .flatMap(i -> move.issue(item.id(), org, 1, "job " + i, EXECUTOR).thenReturn((Object) "ok")
                        .onErrorResume(e -> Mono.just(e)), 12)
                .collectList().block();

        long issued = outcomes.stream().filter("ok"::equals).count();
        long refused = outcomes.stream().filter(o -> o instanceof InsufficientStockException || o instanceof StockChangedException).count();
        assertEquals(5, issued, outcomes.toString());
        assertEquals(7, refused, outcomes.toString());
        StockItemResponse after = retrieve.byId(item.id(), org).block();
        assertEquals(0, after.onHand());
        assertEquals(6, retrieve.history(item.id(), org).block().size());
    }

    @Test
    @DisplayName("an adjustment only applies to the quantity the caller saw; a stale one changes nothing")
    void adjustIsGuarded() {
        UUID org = UUID.randomUUID();
        StockItemResponse item = register(org, "FAN", 1, 10);
        StockItem loaded = repository.findById(item.id()).block();

        StockEntry entry = new StockEntry(Instant.now(), "ADJUSTED", EXECUTOR, -3, 7, "recount");
        assertTrue(repository.adjust(item.id(), loaded.getOnHand(), 7, entry).block());
        assertEquals(false, repository.adjust(item.id(), loaded.getOnHand(), 4, new StockEntry(Instant.now(), "ADJUSTED", EXECUTOR, -6, 4, "stale")).block());
        assertEquals(7, retrieve.byId(item.id(), org).block().onHand());

        move.adjust(item.id(), org, 9, "found a box", EXECUTOR).block();
        assertEquals(9, retrieve.byId(item.id(), org).block().onHand());
    }

    @Test
    @DisplayName("low-stock compares the quantity with the reorder level of the same item, ignoring discontinued items and other tenants")
    void lowStock() {
        UUID org = UUID.randomUUID();
        register(org, "OKAY", 2, 10);
        register(org, "AT-LEVEL", 5, 5);
        register(org, "EMPTY", 3, 0);
        StockItemResponse gone = register(org, "RETIRED", 9, 1);
        control.discontinue(gone.id(), org, EXECUTOR).block();
        register(UUID.randomUUID(), "OTHER-TENANT", 9, 0);

        List<String> low = retrieve.lowStock(org).map(StockItemResponse::sku).collectList().block();

        assertEquals(List.of("AT-LEVEL", "EMPTY"), low);
        assertEquals(List.of("EMPTY"), retrieve.all(org, null, "empty").map(StockItemResponse::sku).collectList().block());
        assertEquals(List.of("RETIRED"), retrieve.all(org, com.thinklab.domain.model.StockItem.StockStatus.DISCONTINUED, null).map(StockItemResponse::sku).collectList().block());
    }

    @Test
    @DisplayName("the history keeps only the newest entries, and a discontinued item accepts no movement; another tenant sees nothing")
    void historyCapAndDiscontinued() {
        UUID org = UUID.randomUUID();
        StockItemResponse item = register(org, "BULK", 1, 0);

        Flux.range(0, StockItem.HISTORY_LIMIT + 20)
                .concatMap(i -> repository.applyDelta(item.id(), 1, new StockEntry(Instant.now(), "RECEIVED", EXECUTOR, 1, i + 1L, "n" + i)))
                .blockLast();

        var history = retrieve.history(item.id(), org).block();
        assertEquals(StockItem.HISTORY_LIMIT, history.size());
        assertEquals("n" + (StockItem.HISTORY_LIMIT + 19), history.get(0).reason());
        assertEquals(StockItem.HISTORY_LIMIT + 20, retrieve.byId(item.id(), org).block().onHand());

        control.discontinue(item.id(), org, EXECUTOR).block();
        assertEquals("DISCONTINUED", retrieve.byId(item.id(), org).block().status());
        assertThrows(com.thinklab.domain.exception.InvalidStockItemStatusException.class, () -> move.receive(item.id(), org, 1, null, EXECUTOR).block());
        assertEquals(false, repository.applyDelta(item.id(), 1, new StockEntry(Instant.now(), "RECEIVED", EXECUTOR, 1, 1, "late")).block());
        assertThrows(StockItemNotFoundException.class, () -> retrieve.byId(item.id(), UUID.randomUUID()).block());
    }
}
