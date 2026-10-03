package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.AdjustmentRequest;
import com.thinklab.application.dto.request.InitiateStockItemRequest;
import com.thinklab.application.dto.request.MovementRequest;
import com.thinklab.application.dto.request.UpdateStockItemRequest;
import com.thinklab.application.dto.response.StockEntryResponse;
import com.thinklab.application.dto.response.StockItemResponse;
import com.thinklab.application.usecase.ControlStockItemUseCase;
import com.thinklab.application.usecase.InitiateStockItemUseCase;
import com.thinklab.application.usecase.MoveStockUseCase;
import com.thinklab.application.usecase.RetrieveStockItemUseCase;
import com.thinklab.application.usecase.UpdateStockItemUseCase;
import com.thinklab.domain.exception.DuplicateStockItemException;
import com.thinklab.domain.exception.InsufficientStockException;
import com.thinklab.domain.exception.StockItemNotFoundException;
import com.thinklab.domain.model.StockItem.StockStatus;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockItemControllerTest {

    private static final String EXECUTOR = "stock-clerk";

    @Mock private InitiateStockItemUseCase initiateStockItemUseCase;
    @Mock private UpdateStockItemUseCase updateStockItemUseCase;
    @Mock private MoveStockUseCase moveStockUseCase;
    @Mock private ControlStockItemUseCase controlStockItemUseCase;
    @Mock private RetrieveStockItemUseCase retrieveStockItemUseCase;

    @InjectMocks private StockItemController controller;

    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private StockItemResponse sample;

    @BeforeEach
    void setUp() {
        sample = new StockItemResponse(id, org, "TONER", "Toner", "unit", 5, 2, false, "ACTIVE", Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("initiate answers 201 Created and propagates a duplicate SKU")
    void initiate() {
        InitiateStockItemRequest request = new InitiateStockItemRequest("TONER", "Toner", "unit", 2L, 5L);
        when(initiateStockItemUseCase.execute(org, request, EXECUTOR)).thenReturn(Mono.just(sample)).thenReturn(Mono.error(new DuplicateStockItemException("dup")));

        StepVerifier.create(controller.initiate(org.toString(), EXECUTOR, request)).assertNext(response -> {
            assertEquals(HttpStatus.CREATED, response.getStatus());
            assertEquals(id, response.body().id());
        }).verifyComplete();
        StepVerifier.create(controller.initiate(org.toString(), EXECUTOR, request)).expectError(DuplicateStockItemException.class).verify();
    }

    @Test
    @DisplayName("retrieve by id is tenant-scoped and answers 200, propagating a 404")
    void retrieveById() {
        when(retrieveStockItemUseCase.byId(id, org)).thenReturn(Mono.just(sample)).thenReturn(Mono.error(new StockItemNotFoundException(id)));

        StepVerifier.create(controller.retrieveById(id, org.toString())).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveById(id, org.toString())).expectError(StockItemNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieve lists the tenant's items, optionally by status and SKU, and the low-stock ones")
    void lists() {
        when(retrieveStockItemUseCase.all(org, null, null)).thenReturn(Flux.just(sample));
        when(retrieveStockItemUseCase.all(org, StockStatus.ACTIVE, "TONER")).thenReturn(Flux.empty());
        when(retrieveStockItemUseCase.lowStock(org)).thenReturn(Flux.just(sample));

        StepVerifier.create(controller.retrieveAll(org.toString(), null, null)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller.retrieveAll(org.toString(), StockStatus.ACTIVE, "TONER")).assertNext(list -> assertEquals(0, list.size())).verifyComplete();
        StepVerifier.create(controller.retrieveLowStock(org.toString())).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("update, receive, issue, adjust and discontinue answer 204, and a refused issue propagates")
    void mutations() {
        UpdateStockItemRequest update = new UpdateStockItemRequest("Toner XL", "box", 4L);
        MovementRequest movement = new MovementRequest(3L, "job");
        AdjustmentRequest adjustment = new AdjustmentRequest(8L, "recount");
        when(updateStockItemUseCase.execute(id, org, update, EXECUTOR)).thenReturn(Mono.empty());
        when(moveStockUseCase.receive(id, org, 3L, "job", EXECUTOR)).thenReturn(Mono.empty());
        when(moveStockUseCase.issue(id, org, 3L, "job", EXECUTOR)).thenReturn(Mono.empty()).thenReturn(Mono.error(new InsufficientStockException("no")));
        when(moveStockUseCase.adjust(id, org, 8L, "recount", EXECUTOR)).thenReturn(Mono.empty());
        when(controlStockItemUseCase.discontinue(id, org, EXECUTOR)).thenReturn(Mono.empty());

        StepVerifier.create(controller.update(id, org.toString(), EXECUTOR, update)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.receive(id, org.toString(), EXECUTOR, movement)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.issue(id, org.toString(), EXECUTOR, movement)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.issue(id, org.toString(), EXECUTOR, movement)).expectError(InsufficientStockException.class).verify();
        StepVerifier.create(controller.adjust(id, org.toString(), EXECUTOR, adjustment)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.controlDiscontinue(id, org.toString(), EXECUTOR)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("history/retrieve returns the tenant-scoped history")
    void history() {
        StockEntryResponse entry = new StockEntryResponse(Instant.now(), "INITIATED", EXECUTOR, 5, 5, "created");
        when(retrieveStockItemUseCase.history(id, org)).thenReturn(Mono.just(List.of(entry)));

        StepVerifier.create(controller.retrieveHistory(id, org.toString())).assertNext(list -> assertEquals("INITIATED", list.get(0).action())).verifyComplete();
    }
}
