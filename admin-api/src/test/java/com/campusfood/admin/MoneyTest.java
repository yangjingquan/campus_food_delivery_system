package com.campusfood.admin;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证金额比例运算的舍入规则和参数边界。 */
class MoneyTest {
    @Test
    void roundsHalfUpToCent() {
        assertEquals(1, Money.percentage(5, new BigDecimal("10")));
        assertEquals(0, Money.percentage(4, new BigDecimal("10")));
        assertEquals(124, Money.percentage(1235, new BigDecimal("10")));
    }

    @Test
    void rejectsInvalidRatesAndNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.percentage(-1, new BigDecimal("10")));
        assertThrows(IllegalArgumentException.class, () -> Money.percentage(100, new BigDecimal("100.01")));
    }
}
