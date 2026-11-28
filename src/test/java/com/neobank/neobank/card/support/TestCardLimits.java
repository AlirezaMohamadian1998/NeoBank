package com.neobank.neobank.card.support;

import com.neobank.neobank.card.limit.CardSpendingLimits;

import java.math.BigDecimal;

public final class TestCardLimits {

    private TestCardLimits() {
    }

    public static CardSpendingLimits generous() {
        return CardSpendingLimits.createNew(new BigDecimal("1000000.00"), new BigDecimal("5000000.00"));
    }
}
