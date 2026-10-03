package com.thinklab.domain.exception;

/**
 * Domain Exception: an issue asked for more than is on hand.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict (ADR-019) - the request is well formed but collides with the current stock.
 */
public class InsufficientStockException extends BusinessException {

    private static final String ERROR_CODE = "ERR-STK-00409";

    public InsufficientStockException(String message) {
        super(ERROR_CODE, message);
    }
}
