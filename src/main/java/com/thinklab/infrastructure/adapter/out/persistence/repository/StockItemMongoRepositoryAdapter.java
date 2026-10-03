package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.PushOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateStockItemException;
import com.thinklab.domain.exception.StockItemNotFoundException;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.model.StockItem.StockStatus;
import com.thinklab.domain.repository.StockItemRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.StockEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.StockItemDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.StockItemDocument.StockItemPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for StockItems. Every change is one atomic update ({@code $set}/{@code $inc} plus a
 * {@code $push} of the history entry, sliced to the newest {@link StockItem#HISTORY_LIMIT}), so the balance and its history can
 * never diverge. The quantity operations carry their guard in the filter (ADR-031): the update only matches while the item is
 * still ACTIVE and still holds what the caller relied on, so a concurrent writer can neither push the balance below zero nor have
 * its movement overwritten.
 */
@Singleton
public class StockItemMongoRepositoryAdapter implements StockItemRepository {

    private final MongoClient mongoClient;
    private final String database;

    public StockItemMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        this.database = MongoSupport.database(mongoUri);
    }

    private MongoCollection<StockItemDocument> collection() {
        return mongoClient.getDatabase(database).getCollection(MongoSupport.COLLECTION, StockItemDocument.class)
                .withCodecRegistry(MongoSupport.POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<StockItem> create(StockItem item) {
        return Mono.from(collection().insertOne(StockItemPersistenceMapper.toDocument(item)))
                .map(result -> item)
                .onErrorMap(e -> MongoSupport.isDuplicateOn(e, StockIndexInitializer.SKU_INDEX),
                        e -> new DuplicateStockItemException("Organisation " + item.getOrganisationId() + " already has a stock item with SKU " + item.getSku() + "."));
    }

    @Override
    public Mono<StockItem> findById(UUID id) {
        return Mono.from(collection().find(Filters.eq("_id", id)).first()).map(StockItemPersistenceMapper::toDomain);
    }

    @Override
    public Flux<StockItem> findAll(UUID organisationId, StockStatus status, String sku) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq("organisationId", organisationId));
        if (status != null) {
            filters.add(Filters.eq("status", status.name()));
        }
        if (sku != null && !sku.isBlank()) {
            filters.add(Filters.eq("sku", sku.trim().toUpperCase(Locale.ROOT)));
        }
        return Flux.from(collection().find(Filters.and(filters)).sort(Sorts.ascending("sku"))).map(StockItemPersistenceMapper::toDomain);
    }

    @Override
    public Flux<StockItem> findLowStock(UUID organisationId) {
        Bson filter = Filters.and(
                Filters.eq("organisationId", organisationId),
                Filters.eq("status", StockStatus.ACTIVE.name()),
                Filters.expr(new Document("$lte", List.of("$onHand", "$reorderLevel"))));
        return Flux.from(collection().find(filter).sort(Sorts.ascending("sku"))).map(StockItemPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Boolean> applyDelta(UUID id, long delta, StockEntry entry) {
        List<Bson> guard = new ArrayList<>();
        guard.add(Filters.eq("_id", id));
        guard.add(Filters.eq("status", StockStatus.ACTIVE.name()));
        if (delta < 0) {
            guard.add(Filters.gte("onHand", -delta));
        }
        Bson update = Updates.combine(Updates.inc("onHand", delta), Updates.set("updatedAt", Instant.now()), pushEntry(entry));
        return Mono.from(collection().updateOne(Filters.and(guard), update)).map(result -> result.getMatchedCount() > 0);
    }

    @Override
    public Mono<Boolean> adjust(UUID id, long expectedOnHand, long newQuantity, StockEntry entry) {
        Bson guard = Filters.and(Filters.eq("_id", id), Filters.eq("status", StockStatus.ACTIVE.name()), Filters.eq("onHand", expectedOnHand));
        Bson update = Updates.combine(Updates.set("onHand", newQuantity), Updates.set("updatedAt", Instant.now()), pushEntry(entry));
        return Mono.from(collection().updateOne(guard, update)).map(result -> result.getMatchedCount() > 0);
    }

    @Override
    public Mono<Void> updateDetails(UUID id, String name, String unit, long reorderLevel, StockEntry entry) {
        return executeUpdate(id, Updates.combine(Updates.set("name", name), Updates.set("unit", unit),
                Updates.set("reorderLevel", reorderLevel), Updates.set("updatedAt", Instant.now()), pushEntry(entry)));
    }

    @Override
    public Mono<Void> updateStatus(UUID id, StockStatus status, StockEntry entry) {
        return executeUpdate(id, Updates.combine(Updates.set("status", status.name()), Updates.set("updatedAt", Instant.now()), pushEntry(entry)));
    }

    private static Bson pushEntry(StockEntry entry) {
        return Updates.pushEach("history", List.of(StockEntryDocument.fromDomain(entry)), new PushOptions().slice(-StockItem.HISTORY_LIMIT));
    }

    private Mono<Void> executeUpdate(UUID id, Bson update) {
        return Mono.from(collection().updateOne(Filters.eq("_id", id), update))
                .flatMap(result -> result.getMatchedCount() == 0 ? Mono.error(new StockItemNotFoundException(id)) : Mono.empty());
    }
}
