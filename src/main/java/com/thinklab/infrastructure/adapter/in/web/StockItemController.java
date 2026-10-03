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
import com.thinklab.domain.model.StockItem.StockStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code consumable-inventory} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.StockItem} is the Control Record; every route
 * follows {@code /consumable-inventory/v1/{control-record-id}/{behavior-qualifier}} and is tenant-scoped by {@code X-Tenant-Id}.
 * There is no {@code DELETE}: no physical delete exists in this Service Domain.
 */
@Controller("/consumable-inventory/v1")
public class StockItemController {

    private static final Logger log = LoggerFactory.getLogger(StockItemController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";

    private final InitiateStockItemUseCase initiateStockItemUseCase;
    private final UpdateStockItemUseCase updateStockItemUseCase;
    private final MoveStockUseCase moveStockUseCase;
    private final ControlStockItemUseCase controlStockItemUseCase;
    private final RetrieveStockItemUseCase retrieveStockItemUseCase;

    public StockItemController(InitiateStockItemUseCase initiateStockItemUseCase, UpdateStockItemUseCase updateStockItemUseCase,
                               MoveStockUseCase moveStockUseCase, ControlStockItemUseCase controlStockItemUseCase,
                               RetrieveStockItemUseCase retrieveStockItemUseCase) {
        this.initiateStockItemUseCase = initiateStockItemUseCase;
        this.updateStockItemUseCase = updateStockItemUseCase;
        this.moveStockUseCase = moveStockUseCase;
        this.controlStockItemUseCase = controlStockItemUseCase;
        this.retrieveStockItemUseCase = retrieveStockItemUseCase;
    }

    /** Behavior Qualifier: {@code initiate}. Registers a new stock item. */
    @Post("/initiate")
    public Mono<HttpResponse<StockItemResponse>> initiate(
            @Header(TENANT_HEADER) @NotBlank String tenantId,
            @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Body @Valid InitiateStockItemRequest request
    ) {
        log.info("[ACTION: INITIATE_STOCK_ITEM] [EXECUTOR: {}] Received request to register SKU {} for organisation: {}", executor, request.sku(), tenantId);

        return initiateStockItemUseCase.execute(UUID.fromString(tenantId), request, executor).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code retrieve}. Fetches a single stock item by UUID, scoped to the tenant. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<StockItemResponse>> retrieveById(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_STOCK_ITEM] Received request to get stock item by ID: {}", id);

        return retrieveStockItemUseCase.byId(id, UUID.fromString(tenantId)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection). The tenant's items by SKU, optionally by status or exact SKU. */
    @Get("/retrieve")
    public Mono<List<StockItemResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId,
                                                     @QueryValue @Nullable StockStatus status, @QueryValue @Nullable String sku) {
        log.info("[ACTION: RETRIEVE_STOCK_ITEMS] Received request to list stock items for organisation: {} status: {} sku: {}", tenantId, status, sku);

        return Mono.defer(() -> retrieveStockItemUseCase.all(UUID.fromString(tenantId), status, sku).collectList());
    }

    /** Behavior Qualifier: {@code low-stock/retrieve}. ACTIVE items at or below their reorder level. */
    @Get("/low-stock/retrieve")
    public Mono<List<StockItemResponse>> retrieveLowStock(@Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_LOW_STOCK] Received request for organisation: {}", tenantId);

        return Mono.defer(() -> retrieveStockItemUseCase.lowStock(UUID.fromString(tenantId)).collectList());
    }

    /** Behavior Qualifier: {@code update}. Replaces name, unit and reorder level. */
    @Put("/{id}/update")
    public Mono<HttpResponse<Void>> update(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Body @Valid UpdateStockItemRequest request) {
        log.info("[ACTION: UPDATE_STOCK_ITEM] [EXECUTOR: {}] Received request to update stock item ID: {}", executor, id);

        return updateStockItemUseCase.execute(id, UUID.fromString(tenantId), request, executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code movement/receive}. Adds to the quantity on hand. */
    @Put("/{id}/movement/receive")
    public Mono<HttpResponse<Void>> receive(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                            @Header(EXECUTOR_HEADER) @NotBlank String executor, @Body @Valid MovementRequest request) {
        log.info("[ACTION: RECEIVE_STOCK] [EXECUTOR: {}] Received {} into stock item ID: {}", executor, request.quantity(), id);

        return moveStockUseCase.receive(id, UUID.fromString(tenantId), request.quantity(), request.reason(), executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code movement/issue}. Takes from the quantity on hand, never below zero. */
    @Put("/{id}/movement/issue")
    public Mono<HttpResponse<Void>> issue(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                          @Header(EXECUTOR_HEADER) @NotBlank String executor, @Body @Valid MovementRequest request) {
        log.info("[ACTION: ISSUE_STOCK] [EXECUTOR: {}] Issued {} from stock item ID: {}", executor, request.quantity(), id);

        return moveStockUseCase.issue(id, UUID.fromString(tenantId), request.quantity(), request.reason(), executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code movement/adjust}. Sets the quantity on hand to a counted value, with a mandatory reason. */
    @Put("/{id}/movement/adjust")
    public Mono<HttpResponse<Void>> adjust(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                           @Header(EXECUTOR_HEADER) @NotBlank String executor, @Body @Valid AdjustmentRequest request) {
        log.info("[ACTION: ADJUST_STOCK] [EXECUTOR: {}] Adjusting stock item ID: {} to {}", executor, id, request.newQuantity());

        return moveStockUseCase.adjust(id, UUID.fromString(tenantId), request.newQuantity(), request.reason(), executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code control/discontinue}. ACTIVE -> DISCONTINUED (terminal). */
    @Put("/{id}/control/discontinue")
    public Mono<HttpResponse<Void>> controlDiscontinue(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                       @Header(EXECUTOR_HEADER) @NotBlank String executor) {
        log.info("[ACTION: CONTROL_STOCK_ITEM] [EXECUTOR: {}] discontinue for ID: {}", executor, id);

        return controlStockItemUseCase.discontinue(id, UUID.fromString(tenantId), executor).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code history/retrieve}. The item's recent history, newest first (the last 1000 entries). */
    @Get("/{id}/history/retrieve")
    public Mono<List<StockEntryResponse>> retrieveHistory(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId) {
        log.info("[ACTION: RETRIEVE_STOCK_HISTORY] Received request for history of stock item ID: {}", id);

        return retrieveStockItemUseCase.history(id, UUID.fromString(tenantId));
    }
}
