package com.campusfood.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record PricingRules(Rule fee, Rule food, Rule delivery) {
    public enum Mode { FIXED, PERCENTAGE }

    // Fixed amounts are cents. Percentages are basis points (100 basis points = 1%).
    public record Rule(Mode mode, int value) {
        public Rule {
            Objects.requireNonNull(mode);
            if (value < 0 || (mode == Mode.PERCENTAGE && value > 10_000)) {
                throw new IllegalArgumentException("计费参数超出范围");
            }
        }

        public long calculate(long baseCents) {
            return mode == Mode.FIXED ? value : BigDecimal.valueOf(baseCents)
                .multiply(BigDecimal.valueOf(value)).movePointLeft(4)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        }

        public long commission(long baseCents) {
            return Math.min(baseCents, calculate(baseCents));
        }
    }

    public static Rule fixed(int cents) {
        return new Rule(Mode.FIXED, cents);
    }

    public static Rule percentage(BigDecimal percent) {
        return new Rule(Mode.PERCENTAGE, percent.movePointRight(2).intValueExact());
    }
}
