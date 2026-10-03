package com.thinklab.domain.model;

import com.thinklab.domain.exception.InsufficientStockException;
import com.thinklab.domain.exception.InvalidStockItemStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Core Domain Model representing the StockItem Aggregate Root (Control Record) of the {@code consumable-inventory} Service
 * Domain: one kind of consumable or spare part (toner, cables, spare SSDs) an organisation keeps, and how many it has (ADR-030).
 *
 * <p>The quantity on hand is a counter on the item, never negative (ADR-031). Stock moves through {@code receive}, {@code issue}
 * and {@code adjust}; each appends a {@link StockEntry} to the item's history, which keeps only the most recent
 * {@link #HISTORY_LIMIT} entries.
 *
 * <p>Lifecycle: {@code ACTIVE -> DISCONTINUED} (terminal). A discontinued item keeps its balance and history but moves no more.
 *
 * <p>Strictly pure Java. Agnostic of frameworks, databases, or web layers.
 */
public class StockItem {

    public static final int HISTORY_LIMIT = 1000;
    static final Pattern SKU = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,63}");

    private final UUID id;
    private final UUID organisationId;
    private final String sku;
    private String name;
    private String unit;
    private long onHand;
    private long reorderLevel;
    private StockStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<StockEntry> history;

    private StockItem(UUID id, UUID organisationId, String sku, String name, String unit, long onHand, long reorderLevel,
                      StockStatus status, Instant createdAt, Instant updatedAt, List<StockEntry> history) {
        this.id = id;
        this.organisationId = organisationId;
        this.sku = sku;
        this.name = name;
        this.unit = unit;
        this.onHand = onHand;
        this.reorderLevel = reorderLevel;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.history = history;
    }

    /** Static factory for aggregate creation (BIAN Behavior Qualifier: {@code initiate}). */
    public static StockItem createNew(UUID id, UUID organisationId, String sku, String name, String unit, long reorderLevel,
                                      long initialQuantity, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for StockItem creation.");
        }
        String normalisedSku = sku == null ? "" : sku.trim().toUpperCase(Locale.ROOT);
        if (!SKU.matcher(normalisedSku).matches()) {
            throw new IllegalArgumentException("SKU must be 1-64 characters: letters, digits, '.', '_' or '-', starting with a letter or digit.");
        }
        requireExecutor(executor);
        requireNonNegative(initialQuantity, "Initial quantity");
        Instant now = Instant.now();
        StockItem item = new StockItem(id, organisationId, normalisedSku, validName(name), validUnit(unit), initialQuantity,
                requireNonNegative(reorderLevel, "Reorder level"), StockStatus.ACTIVE, now, now, new ArrayList<>());
        item.history.add(new StockEntry(now, "INITIATED", executor, initialQuantity, initialQuantity, "Stock item created."));
        return item;
    }

    /** Reconstitutes an existing StockItem aggregate from the persistence layer. */
    public static StockItem reconstitute(UUID id, UUID organisationId, String sku, String name, String unit, long onHand,
                                         long reorderLevel, StockStatus status, Instant createdAt, Instant updatedAt,
                                         List<StockEntry> history) {
        if (id == null || organisationId == null || sku == null || name == null || unit == null || status == null
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Every field except the history is mandatory to reconstitute a StockItem.");
        }
        return new StockItem(id, organisationId, sku, name, unit, onHand, reorderLevel, status, createdAt, updatedAt,
                history == null ? new ArrayList<>() : new ArrayList<>(history));
    }

    /** Behavior Qualifier: {@code movement/receive}. Adds to the quantity on hand. */
    public StockEntry receive(long quantity, String reason, String executor) {
        requireMovable(executor);
        requirePositive(quantity);
        this.onHand += quantity;
        return record("RECEIVED", executor, quantity, reason);
    }

    /** Behavior Qualifier: {@code movement/issue}. Takes from the quantity on hand, never below zero. */
    public StockEntry issue(long quantity, String reason, String executor) {
        requireMovable(executor);
        requirePositive(quantity);
        if (quantity > onHand) {
            throw new InsufficientStockException(String.format(
                    "Cannot issue %d of [%s]: only %d on hand. Receive or adjust the stock first.", quantity, sku, onHand));
        }
        this.onHand -= quantity;
        return record("ISSUED", executor, -quantity, reason);
    }

    /** Behavior Qualifier: {@code movement/adjust}. Sets the quantity on hand to a counted value; a reason is mandatory. */
    public StockEntry adjust(long newQuantity, String reason, String executor) {
        requireMovable(executor);
        requireNonNegative(newQuantity, "Counted quantity");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is mandatory for a stock adjustment.");
        }
        long delta = newQuantity - onHand;
        this.onHand = newQuantity;
        return record("ADJUSTED", executor, delta, reason);
    }

    /** Behavior Qualifier: {@code update}. Replaces name, unit and reorder level; illegal once discontinued. */
    public StockEntry updateDetails(String newName, String newUnit, long newReorderLevel, String executor) {
        requireMovable(executor);
        this.name = validName(newName);
        this.unit = validUnit(newUnit);
        this.reorderLevel = requireNonNegative(newReorderLevel, "Reorder level");
        return record("UPDATED", executor, 0, "Name, unit and reorder level updated.");
    }

    /** Behavior Qualifier: {@code control/discontinue}. {@code ACTIVE -> DISCONTINUED} (terminal). */
    public StockEntry discontinue(String executor) {
        requireMovable(executor);
        this.status = StockStatus.DISCONTINUED;
        return record("DISCONTINUED", executor, 0, "Discontinued.");
    }

    public boolean isBelowReorderLevel() {
        return onHand <= reorderLevel;
    }

    private void requireMovable(String executor) {
        requireExecutor(executor);
        if (status != StockStatus.ACTIVE) {
            throw new InvalidStockItemStatusException(String.format(
                    "Compliance Violation: StockItem [%s] is %s; only ACTIVE items accept changes.", sku, status));
        }
    }

    private StockEntry record(String action, String executor, long quantity, String reason) {
        this.updatedAt = Instant.now();
        StockEntry entry = new StockEntry(updatedAt, action, executor, quantity, onHand, reason);
        history.add(entry);
        if (history.size() > HISTORY_LIMIT) {
            history.remove(0);
        }
        return entry;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is mandatory.");
        }
        return name.trim();
    }

    private static String validUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("Unit is mandatory (for example: unit, box, metre).");
        }
        return unit.trim();
    }

    private static long requireNonNegative(long value, String what) {
        if (value < 0) {
            throw new IllegalArgumentException(what + " cannot be negative.");
        }
        return value;
    }

    private static void requirePositive(long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be a positive number.");
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable StockItem mutations.");
        }
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getUnit() { return unit; }
    public long getOnHand() { return onHand; }
    public long getReorderLevel() { return reorderLevel; }
    public StockStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<StockEntry> getHistory() { return Collections.unmodifiableList(history); }

    /**
     * <pre>
     * ACTIVE -> DISCONTINUED
     * </pre>
     */
    public enum StockStatus { ACTIVE, DISCONTINUED }
}
