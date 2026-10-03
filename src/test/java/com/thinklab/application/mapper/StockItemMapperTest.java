package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.StockEntryResponse;
import com.thinklab.application.dto.response.StockItemResponse;
import com.thinklab.domain.model.StockEntry;
import com.thinklab.domain.model.StockItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockItemMapperTest {

    @Test
    @DisplayName("an item maps field for field, flagging a balance at or under the reorder level, and an entry maps too")
    void mapsEverything() {
        UUID id = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        StockItem item = StockItem.createNew(id, org, "toner", "Toner", "unit", 5, 3, "clerk");

        StockItemResponse response = StockItemMapper.toResponse(item);
        assertEquals(id, response.id());
        assertEquals(org, response.organisationId());
        assertEquals("TONER", response.sku());
        assertEquals("Toner", response.name());
        assertEquals("unit", response.unit());
        assertEquals(3, response.onHand());
        assertEquals(5, response.reorderLevel());
        assertTrue(response.belowReorderLevel());
        assertEquals("ACTIVE", response.status());
        assertEquals(item.getCreatedAt(), response.createdAt());

        StockEntryResponse entry = StockItemMapper.toResponse(new StockEntry(Instant.parse("2026-10-03T12:00:00Z"), "ISSUED", "clerk", -2, 1, "job"));
        assertEquals("ISSUED", entry.action());
        assertEquals(-2, entry.quantity());
        assertEquals(1, entry.balanceAfter());
        assertEquals("job", entry.reason());
    }

    @Test
    @DisplayName("the mapper is a non-instantiable utility class")
    void utilityClass() throws Exception {
        Constructor<StockItemMapper> constructor = StockItemMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }
}
