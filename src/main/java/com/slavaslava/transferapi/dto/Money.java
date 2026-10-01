package com.slavaslava.transferapi.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Money {

    private static final int SCALE = 2;

    private Money() {
    }

    /** Normalises an amount to two decimals so JSON always shows {@code 0.00}, never {@code 0}. */
    static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
