package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import com.thinklab.infrastructure.adapter.out.persistence.entity.StockItemDocument.StockItemPersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentsTest {

    @Test
    @DisplayName("a StockItem survives the document round trip, history included")
    void roundTrip() {
        StockItem item = StockItem.createNew(UUID.randomUUID(), UUID.randomUUID(), "TONER", "Toner", "unit", 2, 10, "clerk");
        item.issue(4, "job", "clerk");

        StockItemDocument document = StockItemPersistenceMapper.toDocument(item);
        assertEquals(6, document.getOnHand());
        assertEquals("ACTIVE", document.getStatus());
        assertEquals(2, document.getHistory().size());
        StockItem back = StockItemPersistenceMapper.toDomain(document);

        assertEquals(item.getId(), back.getId());
        assertEquals(item.getOrganisationId(), back.getOrganisationId());
        assertEquals("TONER", back.getSku());
        assertEquals(6, back.getOnHand());
        assertEquals(2, back.getReorderLevel());
        assertEquals(item.getStatus(), back.getStatus());
        assertEquals(item.getUpdatedAt(), back.getUpdatedAt());
        assertEquals(item.getHistory(), back.getHistory());
    }

    @Test
    @DisplayName("a history entry survives its document form")
    void entryRoundTrip() {
        StockEntry entry = new StockEntry(Instant.parse("2026-10-03T12:00:00Z"), "ISSUED", "clerk", -3, 7, "job");

        assertEquals(entry, StockEntryDocument.fromDomain(entry).toDomain());
    }

    @Test
    @DisplayName("the document exposes every field through plain accessors, as the BSON codec needs")
    void accessors() {
        StockItemDocument doc = new StockItemDocument();
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        doc.setId(id);
        doc.setOrganisationId(id);
        doc.setSku("A");
        doc.setName("n");
        doc.setUnit("u");
        doc.setOnHand(4);
        doc.setReorderLevel(2);
        doc.setStatus("ACTIVE");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        doc.setHistory(List.of());

        assertEquals(id, doc.getId());
        assertEquals(id, doc.getOrganisationId());
        assertEquals("A", doc.getSku());
        assertEquals("n", doc.getName());
        assertEquals("u", doc.getUnit());
        assertEquals(4, doc.getOnHand());
        assertEquals(2, doc.getReorderLevel());
        assertEquals("ACTIVE", doc.getStatus());
        assertEquals(now, doc.getCreatedAt());
        assertEquals(now, doc.getUpdatedAt());
        assertEquals(0, doc.getHistory().size());
    }

    @Test
    @DisplayName("the persistence mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<StockItemPersistenceMapper> constructor = StockItemPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
