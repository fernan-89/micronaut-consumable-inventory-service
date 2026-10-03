package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateStockItemRequest;
import com.thinklab.application.dto.request.UpdateStockItemRequest;
import com.thinklab.domain.exception.DuplicateStockItemException;
import com.thinklab.domain.exception.InsufficientStockException;
import com.thinklab.domain.exception.InvalidStockItemStatusException;
import com.thinklab.domain.exception.StockChangedException;
import com.thinklab.domain.exception.StockItemNotFoundException;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.model.StockItem.StockStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.StockItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockUseCasesTest {

    private static final String EXECUTOR = "stock-clerk";

    @Mock private StockItemRepository repository;
    @Mock private HashServicePort hashServicePort;

    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();
    private StockItemLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new StockItemLookup(repository);
    }

    private StockItem item(long onHand) {
        return StockItem.createNew(id, org, "TONER", "Toner", "unit", 2, onHand, EXECUTOR);
    }

    @Test
    @DisplayName("initiate draws a Sovereign ID, registers the item with its opening balance (default 0) and persists it")
    void initiate() {
        InitiateStockItemUseCase useCase = new InitiateStockItemUseCase(hashServicePort, repository);
        when(hashServicePort.generateSovereignId("stock-item-creation")).thenReturn(Mono.just(id));
        when(repository.create(any(StockItem.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

        StepVerifier.create(useCase.execute(org, new InitiateStockItemRequest("toner", "Toner", "unit", 2L, 10L), EXECUTOR))
                .assertNext(r -> {
                    assertEquals(id, r.id());
                    assertEquals("TONER", r.sku());
                    assertEquals(10, r.onHand());
                    assertEquals("ACTIVE", r.status());
                }).verifyComplete();
        StepVerifier.create(useCase.execute(org, new InitiateStockItemRequest("toner", "Toner", "unit", 2L, null), EXECUTOR))
                .assertNext(r -> assertEquals(0, r.onHand())).verifyComplete();
    }

    @Test
    @DisplayName("initiate surfaces a duplicate SKU and a malformed item")
    void initiateFailures() {
        InitiateStockItemUseCase useCase = new InitiateStockItemUseCase(hashServicePort, repository);
        when(hashServicePort.generateSovereignId("stock-item-creation")).thenReturn(Mono.just(id));
        when(repository.create(any(StockItem.class))).thenReturn(Mono.error(new DuplicateStockItemException("dup")));

        StepVerifier.create(useCase.execute(org, new InitiateStockItemRequest("toner", "Toner", "unit", 2L, 1L), EXECUTOR))
                .expectError(DuplicateStockItemException.class).verify();
        StepVerifier.create(useCase.execute(org, new InitiateStockItemRequest("bad sku", "Toner", "unit", 2L, 1L), EXECUTOR))
                .expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("lookup answers a foreign tenant's item exactly like a missing one")
    void lookupScoping() {
        when(repository.findById(id)).thenReturn(Mono.just(item(1)));

        StepVerifier.create(lookup.owned(id, org)).expectNextCount(1).verifyComplete();
        StepVerifier.create(lookup.owned(id, UUID.randomUUID())).expectError(StockItemNotFoundException.class).verify();
        when(repository.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(lookup.owned(id, org)).expectError(StockItemNotFoundException.class).verify();
    }

    @Test
    @DisplayName("receive and issue apply the signed delta with their history entry")
    void receiveAndIssue() {
        MoveStockUseCase useCase = new MoveStockUseCase(lookup, repository);
        when(repository.findById(id)).thenReturn(Mono.just(item(5)));
        when(repository.applyDelta(eq(id), anyLong(), any(StockEntry.class))).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.receive(id, org, 4, "delivery", EXECUTOR)).verifyComplete();
        verify(repository).applyDelta(eq(id), eq(4L), any(StockEntry.class));

        StepVerifier.create(useCase.issue(id, org, 3, null, EXECUTOR)).verifyComplete();
        ArgumentCaptor<StockEntry> entry = ArgumentCaptor.forClass(StockEntry.class);
        verify(repository).applyDelta(eq(id), eq(-3L), entry.capture());
        assertEquals("ISSUED", entry.getValue().action());
    }

    @Test
    @DisplayName("an issue larger than the balance is refused before any write")
    void issueTooMuch() {
        MoveStockUseCase useCase = new MoveStockUseCase(lookup, repository);
        when(repository.findById(id)).thenReturn(Mono.just(item(2)));

        StepVerifier.create(useCase.issue(id, org, 3, null, EXECUTOR)).expectError(InsufficientStockException.class).verify();
        verify(repository, never()).applyDelta(any(), anyLong(), any());
    }

    @Test
    @DisplayName("when a concurrent writer got in between, the movement is refused as StockChanged and nothing is lost")
    void concurrentChange() {
        MoveStockUseCase useCase = new MoveStockUseCase(lookup, repository);
        when(repository.findById(id)).thenReturn(Mono.just(item(5)));
        when(repository.applyDelta(eq(id), anyLong(), any(StockEntry.class))).thenReturn(Mono.just(false));
        when(repository.adjust(eq(id), anyLong(), anyLong(), any(StockEntry.class))).thenReturn(Mono.just(false));

        StepVerifier.create(useCase.issue(id, org, 1, null, EXECUTOR)).expectError(StockChangedException.class).verify();
        StepVerifier.create(useCase.receive(id, org, 1, null, EXECUTOR)).expectError(StockChangedException.class).verify();
        StepVerifier.create(useCase.adjust(id, org, 9, "recount", EXECUTOR)).expectError(StockChangedException.class).verify();
    }

    @Test
    @DisplayName("adjust sets the counted quantity guarded by the quantity the caller saw")
    void adjust() {
        MoveStockUseCase useCase = new MoveStockUseCase(lookup, repository);
        when(repository.findById(id)).thenReturn(Mono.just(item(5)));
        when(repository.adjust(eq(id), anyLong(), anyLong(), any(StockEntry.class))).thenReturn(Mono.just(true));

        StepVerifier.create(useCase.adjust(id, org, 8, "recount", EXECUTOR)).verifyComplete();

        ArgumentCaptor<StockEntry> entry = ArgumentCaptor.forClass(StockEntry.class);
        verify(repository).adjust(eq(id), eq(5L), eq(8L), entry.capture());
        assertEquals("ADJUSTED", entry.getValue().action());
        assertEquals(3, entry.getValue().quantity());
    }

    @Test
    @DisplayName("movements are refused for a foreign tenant, a discontinued item, a missing reason, never writing")
    void movementRefusals() {
        MoveStockUseCase useCase = new MoveStockUseCase(lookup, repository);
        StockItem discontinued = item(5);
        discontinued.discontinue(EXECUTOR);
        when(repository.findById(id)).thenReturn(Mono.just(item(5))).thenReturn(Mono.just(discontinued)).thenReturn(Mono.just(item(5)));

        StepVerifier.create(useCase.receive(id, UUID.randomUUID(), 1, null, EXECUTOR)).expectError(StockItemNotFoundException.class).verify();
        StepVerifier.create(useCase.receive(id, org, 1, null, EXECUTOR)).expectError(InvalidStockItemStatusException.class).verify();
        StepVerifier.create(useCase.adjust(id, org, 1, " ", EXECUTOR)).expectError(IllegalArgumentException.class).verify();
        verify(repository, never()).applyDelta(any(), anyLong(), any());
        verify(repository, never()).adjust(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("update persists the new details with their entry, and is refused for a foreign tenant or a discontinued item")
    void update() {
        UpdateStockItemUseCase useCase = new UpdateStockItemUseCase(lookup, repository);
        StockItem discontinued = item(5);
        discontinued.discontinue(EXECUTOR);
        when(repository.findById(id)).thenReturn(Mono.just(item(5))).thenReturn(Mono.just(item(5))).thenReturn(Mono.just(discontinued));
        when(repository.updateDetails(eq(id), any(), any(), anyLong(), any(StockEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(id, org, new UpdateStockItemRequest("Toner XL", "box", 4L), EXECUTOR)).verifyComplete();
        verify(repository).updateDetails(eq(id), eq("Toner XL"), eq("box"), eq(4L), any(StockEntry.class));

        StepVerifier.create(useCase.execute(id, UUID.randomUUID(), new UpdateStockItemRequest("x", "u", 1L), EXECUTOR)).expectError(StockItemNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(id, org, new UpdateStockItemRequest("x", "u", 1L), EXECUTOR)).expectError(InvalidStockItemStatusException.class).verify();
    }

    @Test
    @DisplayName("discontinue persists the new status with its entry; a second discontinue and a foreign tenant are refused")
    void discontinue() {
        ControlStockItemUseCase useCase = new ControlStockItemUseCase(lookup, repository);
        StockItem already = item(5);
        already.discontinue(EXECUTOR);
        when(repository.findById(id)).thenReturn(Mono.just(item(5))).thenReturn(Mono.just(already)).thenReturn(Mono.just(item(5)));
        when(repository.updateStatus(eq(id), any(StockStatus.class), any(StockEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(useCase.discontinue(id, org, EXECUTOR)).verifyComplete();
        verify(repository).updateStatus(eq(id), eq(StockStatus.DISCONTINUED), any(StockEntry.class));
        StepVerifier.create(useCase.discontinue(id, org, EXECUTOR)).expectError(InvalidStockItemStatusException.class).verify();
        StepVerifier.create(useCase.discontinue(id, UUID.randomUUID(), EXECUTOR)).expectError(StockItemNotFoundException.class).verify();
    }

    @Test
    @DisplayName("retrieve maps by id, the tenant's list, the low-stock list and the history newest first, all tenant-scoped")
    void retrieve() {
        RetrieveStockItemUseCase useCase = new RetrieveStockItemUseCase(lookup, repository);
        StockItem stocked = item(5);
        stocked.issue(3, "job", EXECUTOR);
        when(repository.findById(id)).thenReturn(Mono.just(stocked));
        when(repository.findAll(org, null, null)).thenReturn(Flux.just(stocked));
        when(repository.findAll(org, StockStatus.DISCONTINUED, "TONER")).thenReturn(Flux.empty());
        when(repository.findLowStock(org)).thenReturn(Flux.just(stocked));

        StepVerifier.create(useCase.byId(id, org)).assertNext(r -> {
            assertEquals(2, r.onHand());
            assertEquals(true, r.belowReorderLevel());
        }).verifyComplete();
        StepVerifier.create(useCase.byId(id, UUID.randomUUID())).expectError(StockItemNotFoundException.class).verify();
        StepVerifier.create(useCase.all(org, null, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.all(org, StockStatus.DISCONTINUED, "TONER")).verifyComplete();
        StepVerifier.create(useCase.lowStock(org)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.history(id, org)).assertNext(history -> {
            assertEquals(2, history.size());
            assertEquals("ISSUED", history.get(0).action());
            assertEquals("INITIATED", history.get(1).action());
        }).verifyComplete();
        StepVerifier.create(useCase.history(id, UUID.randomUUID())).expectError(StockItemNotFoundException.class).verify();
    }
}
