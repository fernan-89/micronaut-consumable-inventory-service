package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateStockItemException;
import com.thinklab.domain.exception.StockItemNotFoundException;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.model.StockItem.StockStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.StockItemDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.StockItemDocument.StockItemPersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class StockItemMongoRepositoryAdapterTest {

    private static final CodecRegistry REGISTRY = CodecRegistries.withUuidRepresentation(
            CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())),
            org.bson.UuidRepresentation.STANDARD);

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<StockItemDocument> collection;

    private StockItemMongoRepositoryAdapter adapter;
    private final UUID id = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private StockItem item;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("stock_db")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("stock_items", StockItemDocument.class)).thenReturn(collection);
        when(collection.withCodecRegistry(any())).thenReturn(collection);
        adapter = new StockItemMongoRepositoryAdapter(mongoClient, "mongodb://localhost:27017/stock_db");
        item = StockItem.createNew(id, tenant, "TONER", "Toner", "unit", 2, 10, "clerk");
    }

    private static BsonDocument render(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, REGISTRY);
    }

    private static MongoWriteException writeError(int code, String message) {
        return new MongoWriteException(new WriteError(code, message, new BsonDocument()), new ServerAddress());
    }

    private FindPublisher<StockItemDocument> streaming() {
        FindPublisher<StockItemDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<StockItemDocument> subscriber = invocation.getArgument(0);
            Flux.just(StockItemPersistenceMapper.toDocument(item)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
        return publisher;
    }

    @Test
    @DisplayName("create inserts the mapped document and emits the aggregate")
    void create() {
        when(collection.insertOne(any(StockItemDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));

        StepVerifier.create(adapter.create(item)).expectNextMatches(saved -> saved.getId().equals(id)).verifyComplete();

        ArgumentCaptor<StockItemDocument> captor = ArgumentCaptor.forClass(StockItemDocument.class);
        verify(collection).insertOne(captor.capture());
        assertEquals("TONER", captor.getValue().getSku());
        assertEquals(10, captor.getValue().getOnHand());
    }

    @Test
    @DisplayName("create maps a duplicate on the SKU index to DuplicateStockItemException and propagates anything else")
    void createDuplicate() {
        MongoWriteException other = writeError(11000, "E11000 duplicate key error index: _id_ dup key");
        when(collection.insertOne(any(StockItemDocument.class)))
                .thenReturn(Mono.error(writeError(11000, "E11000 duplicate key error index: organisationId_1_sku_1 dup key")))
                .thenReturn(Mono.error(other));

        StepVerifier.create(adapter.create(item)).expectError(DuplicateStockItemException.class).verify();
        StepVerifier.create(adapter.create(item)).expectErrorMatches(e -> e == other).verify();
    }

    @Test
    @DisplayName("findById maps the document back, or completes empty")
    void findById() {
        FindPublisher<StockItemDocument> publisher = mock(FindPublisher.class);
        when(collection.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(Mono.just(StockItemPersistenceMapper.toDocument(item))).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findById(id)).expectNextMatches(i -> i.getSku().equals("TONER")).verifyComplete();
        StepVerifier.create(adapter.findById(id)).verifyComplete();
    }

    @Test
    @DisplayName("findAll always filters by tenant and adds status and an upper-cased SKU only when given")
    void findAll() {
        streaming();

        StepVerifier.create(adapter.findAll(tenant, StockStatus.ACTIVE, " toner ")).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant, null, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(tenant, null, " ")).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filters = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(3)).find(filters.capture());
        String full = render(filters.getAllValues().get(0)).toJson();
        assertTrue(full.contains("organisationId") && full.contains("ACTIVE") && full.contains("TONER"));
        String tenantOnly = render(filters.getAllValues().get(1)).toJson();
        assertTrue(tenantOnly.contains("organisationId") && !tenantOnly.contains("status") && !tenantOnly.contains("sku"));
        assertTrue(!render(filters.getAllValues().get(2)).toJson().contains("sku"));
    }

    @Test
    @DisplayName("findLowStock asks for the tenant's ACTIVE items whose quantity is at or under the reorder level")
    void findLowStock() {
        streaming();

        StepVerifier.create(adapter.findLowStock(tenant)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(filter.capture());
        String json = render(filter.getValue()).toJson();
        assertTrue(json.contains("organisationId") && json.contains("ACTIVE") && json.contains("$lte") && json.contains("$onHand") && json.contains("$reorderLevel"));
    }

    @Test
    @DisplayName("applyDelta of an issue guards on ACTIVE and on enough on hand, then $incs, stamps and pushes a sliced history entry")
    void applyDeltaIssue() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));
        StockEntry entry = item.issue(3, "job", "clerk");

        StepVerifier.create(adapter.applyDelta(id, -3, entry)).expectNext(true).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(guard.capture(), update.capture());
        String filter = render(guard.getValue()).toJson();
        assertTrue(filter.contains("ACTIVE") && filter.contains("$gte"));
        BsonDocument doc = render(update.getValue());
        assertEquals(-3, doc.getDocument("$inc").getInt64("onHand").getValue());
        assertTrue(doc.getDocument("$set").containsKey("updatedAt"));
        BsonDocument push = doc.getDocument("$push").getDocument("history");
        assertEquals(-StockItem.HISTORY_LIMIT, push.getInt32("$slice").getValue());
        assertEquals("ISSUED", push.getArray("$each").get(0).asDocument().getString("action").getValue());
    }

    @Test
    @DisplayName("applyDelta of a receipt has no quantity guard, and answers false when nothing matched")
    void applyDeltaReceiptAndMiss() {
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        StockEntry entry = item.receive(2, null, "clerk");

        StepVerifier.create(adapter.applyDelta(id, 2, entry)).expectNext(false).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        verify(collection).updateOne(guard.capture(), any(Bson.class));
        assertTrue(!render(guard.getValue()).toJson().contains("$gte"));
    }

    @Test
    @DisplayName("adjust guards on ACTIVE and on the expected quantity, $sets the new one and pushes the entry; false when nothing matched")
    void adjust() {
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        StockEntry entry = item.adjust(7, "recount", "clerk");

        StepVerifier.create(adapter.adjust(id, 10, 7, entry)).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.adjust(id, 10, 7, entry)).expectNext(false).verifyComplete();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(2)).updateOne(guard.capture(), update.capture());
        String filter = render(guard.getAllValues().get(0)).toJson();
        assertTrue(filter.contains("ACTIVE") && filter.contains("onHand"));
        assertEquals(7, render(update.getAllValues().get(0)).getDocument("$set").getInt64("onHand").getValue());
    }

    @Test
    @DisplayName("updateDetails and updateStatus $set their fields and push the entry; a missing item is StockItemNotFoundException")
    void detailsAndStatus() {
        when(collection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        StockEntry updated = item.updateDetails("Toner XL", "box", 5, "clerk");
        StockEntry discontinued = item.discontinue("clerk");

        StepVerifier.create(adapter.updateDetails(id, "Toner XL", "box", 5, updated)).verifyComplete();
        StepVerifier.create(adapter.updateStatus(id, StockStatus.DISCONTINUED, discontinued)).verifyComplete();
        StepVerifier.create(adapter.updateDetails(id, "n", "u", 1, updated)).expectError(StockItemNotFoundException.class).verify();
        StepVerifier.create(adapter.updateStatus(id, StockStatus.DISCONTINUED, discontinued)).expectError(StockItemNotFoundException.class).verify();

        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(collection, times(4)).updateOne(any(Bson.class), update.capture());
        BsonDocument details = render(update.getAllValues().get(0));
        assertEquals("Toner XL", details.getDocument("$set").getString("name").getValue());
        assertEquals("box", details.getDocument("$set").getString("unit").getValue());
        assertEquals(5, details.getDocument("$set").getInt64("reorderLevel").getValue());
        BsonDocument status = render(update.getAllValues().get(1));
        assertEquals("DISCONTINUED", status.getDocument("$set").getString("status").getValue());
        assertEquals("DISCONTINUED", status.getDocument("$push").getDocument("history").getArray("$each").get(0).asDocument().getString("action").getValue());
    }
}
