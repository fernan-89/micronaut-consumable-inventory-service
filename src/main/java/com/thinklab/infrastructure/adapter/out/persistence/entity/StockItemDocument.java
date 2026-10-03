package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.StockItem;
import com.thinklab.domain.model.StockItem.StockStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure-specific representation of the StockItem aggregate for MongoDB. Must be a top-level {@code public} class: a
 * package-private BSON entity passes every mocked test but fails on the first real write (the POJO codec never calls
 * {@code setAccessible(true)}).
 */
@Introspected
public class StockItemDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String sku;
    private String name;
    private String unit;
    private long onHand;
    private long reorderLevel;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<StockEntryDocument> history = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public long getOnHand() { return onHand; }
    public void setOnHand(long onHand) { this.onHand = onHand; }
    public long getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(long reorderLevel) { this.reorderLevel = reorderLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<StockEntryDocument> getHistory() { return history; }
    public void setHistory(List<StockEntryDocument> history) { this.history = history; }

    /** Strict isolation between Document and Domain. */
    public static final class StockItemPersistenceMapper {

        private StockItemPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static StockItemDocument toDocument(StockItem item) {
            StockItemDocument doc = new StockItemDocument();
            doc.setId(item.getId());
            doc.setOrganisationId(item.getOrganisationId());
            doc.setSku(item.getSku());
            doc.setName(item.getName());
            doc.setUnit(item.getUnit());
            doc.setOnHand(item.getOnHand());
            doc.setReorderLevel(item.getReorderLevel());
            doc.setStatus(item.getStatus().name());
            doc.setCreatedAt(item.getCreatedAt());
            doc.setUpdatedAt(item.getUpdatedAt());
            doc.setHistory(item.getHistory().stream().map(StockEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static StockItem toDomain(StockItemDocument doc) {
            return StockItem.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getSku(), doc.getName(), doc.getUnit(), doc.getOnHand(),
                    doc.getReorderLevel(), StockStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getHistory().stream().map(StockEntryDocument::toDomain).toList());
        }
    }
}
