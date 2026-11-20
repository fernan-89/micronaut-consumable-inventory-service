package com.thinklab.infrastructure.adapter.out.persistence;

import com.thinklab.application.dto.request.InitiateStockItemRequest;
import com.thinklab.application.usecase.ControlStockItemUseCase;
import com.thinklab.application.usecase.InitiateStockItemUseCase;
import com.thinklab.domain.exception.InvalidStockItemStatusException;
import com.thinklab.domain.repository.StockItemRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The discontinue race against a real MongoDB (platform race audit, ACTION-PLAN 0.5): ten discontinuations of one stock item at once have exactly one
 * winner. (The hash service is the test double of {@link StockPersistenceIT}; the stock movements themselves were already guarded.)
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransitionRaceIT implements TestPropertyProvider {

    private static final String DATABASE = "it_consumable_inventory_race_it";
    private static final String EXECUTOR = "stock-admin";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE));
    }

    @Inject InitiateStockItemUseCase initiate;
    @Inject ControlStockItemUseCase control;
    @Inject StockItemRepository repository;

    @Test
    @DisplayName("ten discontinuations of one stock item at once: exactly one wins, the others are refused with a conflict")
    void tenDiscontinuationsAtOnce() {
        UUID org = UUID.randomUUID();
        var item = initiate.execute(org, new InitiateStockItemRequest("RACE-1", "Cable", "unit", 1L, 5L), EXECUTOR).block();

        List<String> results = Flux.range(0, 10)
                .flatMap(i -> control.discontinue(item.id(), org, EXECUTOR).thenReturn("OK").onErrorResume(e -> Mono.just(e.getClass().getSimpleName())), 10)
                .collectList().block(Duration.ofSeconds(30));

        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals(9, results.stream().filter(InvalidStockItemStatusException.class.getSimpleName()::equals).count(), results.toString());
        var stored = repository.findById(item.id(), item.organisationId()).block();
        assertEquals(2, stored.getHistory().size(), "one entry for the opening stock and one for the single discontinuation that won");
    }
}
