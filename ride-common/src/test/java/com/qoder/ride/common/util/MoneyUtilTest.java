package com.qoder.ride.common.util;

import com.qoder.ride.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyUtilTest {

    @Test
    void feeRoundsHalfUp() {
        assertEquals(200, MoneyUtil.fee(1000, 2000));
        assertEquals(25, MoneyUtil.fee(123, 2000));
        assertEquals(3, MoneyUtil.fee(25, 1000));
        assertEquals(2, MoneyUtil.fee(24, 1000));
        assertEquals(5, MoneyUtil.fee(24, 2000));
        assertEquals(0, MoneyUtil.fee(2, 2000));
        assertEquals(0, MoneyUtil.fee(0, 2000));
    }

    @Test
    void feeKeepsLongPrecision() {
        long amount = 999_999_999L;
        assertEquals(199_999_999L + 1, MoneyUtil.fee(amount, 2000));
    }

    @Test
    void requireValidRejectsBadAmounts() {
        assertThrows(BizException.class, () -> MoneyUtil.requireValid(0));
        assertThrows(BizException.class, () -> MoneyUtil.requireValid(-1));
        assertThrows(BizException.class, () -> MoneyUtil.requireValid(MoneyUtil.MAX_AMOUNT + 1));
        assertEquals(1, MoneyUtil.requireValid(1));
        assertEquals(MoneyUtil.MAX_AMOUNT, MoneyUtil.requireValid(MoneyUtil.MAX_AMOUNT));
    }
}