package com.thinklab.domain.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockExceptionsTest {

    @Test
    @DisplayName("insufficient stock and a concurrent change are both ERR-STK-00409")
    void conflicts() {
        assertEquals("ERR-STK-00409", new InsufficientStockException("not enough").getErrorCode());
        assertEquals("ERR-STK-00409", new StockChangedException("changed").getErrorCode());
        assertEquals("not enough", new InsufficientStockException("not enough").getMessage());
    }
}
