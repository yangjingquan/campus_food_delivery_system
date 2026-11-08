package com.campusfood.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 以分为单位计算金额，避免使用浮点数处理资金。 */
public final class Money {
    private Money() {
    }

    /** 按百分比计算金额并对结果四舍五入到整数分。 */
    public static long percentage(long amountCents, BigDecimal percent) {
        if (amountCents < 0 || percent == null || percent.signum() < 0 || percent.compareTo(new BigDecimal("100")) > 0)
            throw new IllegalArgumentException("金额或比例无效");
        return BigDecimal.valueOf(amountCents).multiply(percent).divide(new BigDecimal("100"), 0,
                RoundingMode.HALF_UP).longValueExact();
    }
}
