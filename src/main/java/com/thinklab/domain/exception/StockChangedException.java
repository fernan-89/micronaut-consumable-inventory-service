package com.thinklab.domain.exception;

/**
 * Domain Exception: a concurrent writer changed the item between the check and the write, so the movement was not applied.
 * Safe to retry after re-reading the item.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019).
 */
public class StockChangedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-STK-00409";

    public StockChangedException(String message) {
        super(ERROR_CODE, message);
    }
}
