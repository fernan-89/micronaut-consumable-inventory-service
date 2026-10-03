package com.thinklab.domain.exception;

/**
 * Domain Exception: Thrown when an StockItem is initiated with a SKU that already exists
 * within the same organisation.
 *
 * <p>RFC 7807 mapping: HTTP 409 Conflict.
 */
public class DuplicateStockItemException extends BusinessException {

    private static final String ERROR_CODE = "ERR-STK-00409";

    public DuplicateStockItemException(String message) {
        super(ERROR_CODE, message);
    }
}
