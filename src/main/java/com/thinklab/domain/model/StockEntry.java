package com.thinklab.domain.model;

import java.time.Instant;

/**
 * One line of a stock item's history: what happened, who did it, by how much and what the balance was afterwards.
 *
 * @param quantity     the signed change to the quantity on hand (received +, issued -, adjusted = the difference), 0 for
 *                     entries that do not move stock (initiated with no quantity, updated, discontinued)
 * @param balanceAfter the quantity on hand once this entry applied
 */
public record StockEntry(Instant occurredAt, String action, String executor, long quantity, long balanceAfter, String reason) {}
