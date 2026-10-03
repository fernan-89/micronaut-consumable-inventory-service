package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates, at startup, the unique {@code (organisationId, sku)} index (ADR-031): an organisation has each SKU once. The use case
 * does not pre-check: the index is the arbiter, and the repository turns its duplicate-key error into a clean 409, so two
 * concurrent registrations of the same SKU leave exactly one. Fail-open like the kit's initializer: errors are logged and the
 * application still starts ({@code thinklab.mongo.create-indexes=false} turns it off for unit-test contexts).
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class StockIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String SKU_INDEX = "organisationId_1_sku_1";

    private static final Logger log = LoggerFactory.getLogger(StockIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public StockIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    /** Test seam: how long to wait for the server. */
    StockIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        this.database = MongoSupport.database(mongoUri);
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(MongoSupport.COLLECTION)
                    .createIndex(new Document("organisationId", 1).append("sku", 1), new IndexOptions().unique(true).name(SKU_INDEX))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", SKU_INDEX, database, MongoSupport.COLLECTION);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", SKU_INDEX, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", SKU_INDEX, database, MongoSupport.COLLECTION, e.getMessage());
        }
    }
}
