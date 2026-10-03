package com.thinklab.domain.model;

import com.thinklab.domain.exception.InsufficientStockException;
import com.thinklab.domain.exception.InvalidStockItemStatusException;
import com.thinklab.domain.model.StockItem.StockStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockItemTest {

    private static final String EXECUTOR = "stock-clerk";
    private final UUID id = UUID.randomUUID();
    private final UUID org = UUID.randomUUID();

    private StockItem item(long onHand, long reorderLevel) {
        return StockItem.createNew(id, org, " toner-hp-85a ", "  HP 85A toner ", " unit ", reorderLevel, onHand, EXECUTOR);
    }

    @Test
    @DisplayName("createNew registers an ACTIVE item with a normalised SKU, trimmed fields and an INITIATED entry holding the opening balance")
    void createNew() {
        StockItem item = item(12, 3);

        assertEquals("TONER-HP-85A", item.getSku());
        assertEquals("HP 85A toner", item.getName());
        assertEquals("unit", item.getUnit());
        assertEquals(12, item.getOnHand());
        assertEquals(3, item.getReorderLevel());
        assertEquals(StockStatus.ACTIVE, item.getStatus());
        assertEquals(1, item.getHistory().size());
        StockEntry first = item.getHistory().get(0);
        assertEquals("INITIATED", first.action());
        assertEquals(12, first.quantity());
        assertEquals(12, first.balanceAfter());
    }

    @Test
    @DisplayName("createNew refuses missing ids, a malformed SKU, a missing executor, blank name/unit and negative numbers")
    void createNewGuards() {
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(null, org, "A", "n", "u", 0, 0, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, null, "A", "n", "u", 0, 0, EXECUTOR));
        for (String bad : new String[]{null, "", " ", "-A", "A B", "A/B", "A".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, bad, "n", "u", 0, 0, EXECUTOR), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", "n", "u", 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", "n", "u", 0, 0, " "));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", null, "u", 0, 0, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", " ", "u", 0, 0, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", "n", null, 0, 0, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", "n", " ", 0, 0, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", "n", "u", -1, 0, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> StockItem.createNew(id, org, "A", "n", "u", 0, -1, EXECUTOR));
    }

    @Test
    @DisplayName("receive adds to the balance and records RECEIVED with the positive delta")
    void receive() {
        StockItem item = item(5, 2);

        StockEntry entry = item.receive(7, "delivery", EXECUTOR);

        assertEquals(12, item.getOnHand());
        assertEquals("RECEIVED", entry.action());
        assertEquals(7, entry.quantity());
        assertEquals(12, entry.balanceAfter());
        assertEquals("delivery", entry.reason());
        assertEquals(2, item.getHistory().size());
    }

    @Test
    @DisplayName("issue takes from the balance and records ISSUED with the negative delta, down to exactly zero")
    void issue() {
        StockItem item = item(5, 2);

        StockEntry entry = item.issue(3, null, EXECUTOR);
        assertEquals(2, item.getOnHand());
        assertEquals("ISSUED", entry.action());
        assertEquals(-3, entry.quantity());
        assertEquals(2, entry.balanceAfter());

        item.issue(2, "last ones", EXECUTOR);
        assertEquals(0, item.getOnHand());
    }

    @Test
    @DisplayName("an issue larger than the balance is refused and changes nothing")
    void issueTooMuch() {
        StockItem item = item(5, 2);

        InsufficientStockException failure = assertThrows(InsufficientStockException.class, () -> item.issue(6, null, EXECUTOR));

        assertTrue(failure.getMessage().contains("only 5 on hand"));
        assertEquals(5, item.getOnHand());
        assertEquals(1, item.getHistory().size());
    }

    @Test
    @DisplayName("receive and issue need a positive quantity")
    void positiveQuantities() {
        StockItem item = item(5, 2);
        assertThrows(IllegalArgumentException.class, () -> item.receive(0, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.receive(-1, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.issue(0, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.issue(-1, null, EXECUTOR));
    }

    @Test
    @DisplayName("adjust sets the counted quantity, records the difference as ADJUSTED, and demands a reason")
    void adjust() {
        StockItem item = item(10, 2);

        StockEntry down = item.adjust(7, "recount", EXECUTOR);
        assertEquals(7, item.getOnHand());
        assertEquals("ADJUSTED", down.action());
        assertEquals(-3, down.quantity());
        assertEquals(7, down.balanceAfter());

        StockEntry up = item.adjust(9, "found a box", EXECUTOR);
        assertEquals(2, up.quantity());
        assertEquals(0, item.adjust(0, "all gone", EXECUTOR).balanceAfter());

        assertThrows(IllegalArgumentException.class, () -> item.adjust(-1, "x", EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.adjust(1, null, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.adjust(1, " ", EXECUTOR));
        assertEquals(0, item.getOnHand());
    }

    @Test
    @DisplayName("updateDetails replaces name, unit and reorder level and records UPDATED without moving stock")
    void updateDetails() {
        StockItem item = item(4, 2);

        StockEntry entry = item.updateDetails("HP 85A toner (2 pack)", "pack", 6, EXECUTOR);

        assertEquals("HP 85A toner (2 pack)", item.getName());
        assertEquals("pack", item.getUnit());
        assertEquals(6, item.getReorderLevel());
        assertEquals(4, item.getOnHand());
        assertEquals("UPDATED", entry.action());
        assertEquals(0, entry.quantity());
        assertThrows(IllegalArgumentException.class, () -> item.updateDetails(" ", "u", 1, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.updateDetails("n", null, 1, EXECUTOR));
        assertThrows(IllegalArgumentException.class, () -> item.updateDetails("n", "u", -1, EXECUTOR));
    }

    @Test
    @DisplayName("a discontinued item keeps its balance but accepts no change, and cannot be discontinued twice")
    void discontinued() {
        StockItem item = item(4, 2);

        StockEntry entry = item.discontinue(EXECUTOR);

        assertEquals(StockStatus.DISCONTINUED, item.getStatus());
        assertEquals("DISCONTINUED", entry.action());
        assertEquals(4, entry.balanceAfter());
        assertThrows(InvalidStockItemStatusException.class, () -> item.receive(1, null, EXECUTOR));
        assertThrows(InvalidStockItemStatusException.class, () -> item.issue(1, null, EXECUTOR));
        assertThrows(InvalidStockItemStatusException.class, () -> item.adjust(1, "x", EXECUTOR));
        assertThrows(InvalidStockItemStatusException.class, () -> item.updateDetails("n", "u", 1, EXECUTOR));
        assertThrows(InvalidStockItemStatusException.class, () -> item.discontinue(EXECUTOR));
        assertEquals(4, item.getOnHand());
    }

    @Test
    @DisplayName("every change needs an executor")
    void executorRequired() {
        StockItem item = item(4, 2);
        assertThrows(IllegalArgumentException.class, () -> item.receive(1, null, null));
        assertThrows(IllegalArgumentException.class, () -> item.issue(1, null, " "));
        assertThrows(IllegalArgumentException.class, () -> item.discontinue(null));
    }

    @Test
    @DisplayName("below the reorder level means at or under it")
    void belowReorderLevel() {
        assertTrue(item(2, 2).isBelowReorderLevel());
        assertTrue(item(1, 2).isBelowReorderLevel());
        assertFalse(item(3, 2).isBelowReorderLevel());
        assertTrue(item(0, 0).isBelowReorderLevel());
    }

    @Test
    @DisplayName("the history keeps only the newest entries")
    void historyIsCapped() {
        StockItem item = item(0, 0);

        for (int i = 0; i < StockItem.HISTORY_LIMIT + 5; i++) {
            item.receive(1, "n" + i, EXECUTOR);
        }

        assertEquals(StockItem.HISTORY_LIMIT, item.getHistory().size());
        assertEquals(StockItem.HISTORY_LIMIT + 5, item.getOnHand());
        assertEquals("n" + (StockItem.HISTORY_LIMIT + 4), item.getHistory().get(StockItem.HISTORY_LIMIT - 1).reason());
        assertThrows(UnsupportedOperationException.class, () -> item.getHistory().clear());
    }

    @Test
    @DisplayName("reconstitute rebuilds the aggregate, tolerating a missing history, and refuses missing fields")
    void reconstitute() {
        Instant now = Instant.now();
        StockItem rebuilt = StockItem.reconstitute(id, org, "A", "n", "u", 5, 1, StockStatus.ACTIVE, now, now, null);
        assertEquals(5, rebuilt.getOnHand());
        assertTrue(rebuilt.getHistory().isEmpty());
        assertEquals(now, rebuilt.getCreatedAt());
        assertEquals(now, rebuilt.getUpdatedAt());
        assertEquals(org, rebuilt.getOrganisationId());
        assertEquals(id, rebuilt.getId());

        StockEntry entry = new StockEntry(now, "INITIATED", EXECUTOR, 5, 5, "r");
        assertEquals(1, StockItem.reconstitute(id, org, "A", "n", "u", 5, 1, StockStatus.ACTIVE, now, now, List.of(entry)).getHistory().size());

        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(null, org, "A", "n", "u", 0, 0, StockStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(id, null, "A", "n", "u", 0, 0, StockStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(id, org, null, "n", "u", 0, 0, StockStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(id, org, "A", null, "u", 0, 0, StockStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(id, org, "A", "n", null, 0, 0, StockStatus.ACTIVE, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(id, org, "A", "n", "u", 0, 0, null, now, now, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(id, org, "A", "n", "u", 0, 0, StockStatus.ACTIVE, null, now, null));
        assertThrows(IllegalArgumentException.class, () -> StockItem.reconstitute(id, org, "A", "n", "u", 0, 0, StockStatus.ACTIVE, now, null, null));
    }
}
