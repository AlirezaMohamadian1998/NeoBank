package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.limit.CardLimitProperties.CurrencyLimits;
import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.util.Map;

public final class TestCardLimitProperties {

    private TestCardLimitProperties() {
    }

    public static CardLimitProperties standard() {
        return new CardLimitProperties(Map.of(
                CurrencyCode.USD, currencyLimits("1000.00", "2500.00"),
                CurrencyCode.EUR, currencyLimits("1000.00", "2500.00"),
                CurrencyCode.GBP, currencyLimits("1000.00", "2500.00"),
                CurrencyCode.TRY, currencyLimits("25000.00", "50000.00")
        ));
    }

    private static CurrencyLimits currencyLimits(String defaultPerTransaction, String defaultDaily) {
        return new CurrencyLimits(
                new BigDecimal(defaultPerTransaction),
                new BigDecimal(defaultDaily),
                new BigDecimal(defaultPerTransaction).multiply(BigDecimal.TEN),
                new BigDecimal(defaultDaily).multiply(BigDecimal.TEN)
        );
    }
}
