package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.CardSpendingLimits;
import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;

@ConfigurationProperties(prefix = "card.limits")
@Validated
public record CardLimitProperties(
        @NotNull
        Map<CurrencyCode, @Valid @NotNull CurrencyLimits> currencies
) {

    public CardLimitProperties {
        currencies = currencies == null ? null : Map.copyOf(currencies);
    }

    @AssertTrue(message = "card.limits must define limits for every currency")
    public boolean isEveryCurrencyConfigured() {
        return currencies == null || currencies.keySet().containsAll(EnumSet.allOf(CurrencyCode.class));
    }

    public CurrencyLimits limitsFor(CurrencyCode currency) {
        CurrencyLimits limits = currencies.get(currency);

        if (limits == null) {
            throw new IllegalStateException("No card limits configured for currency " + currency);
        }

        return limits;
    }

    public record CurrencyLimits(
            @NotNull @Positive @Digits(integer = 17, fraction = 2)
            BigDecimal defaultPerTransaction,

            @NotNull @Positive @Digits(integer = 17, fraction = 2)
            BigDecimal defaultDaily,

            @NotNull @Positive @Digits(integer = 17, fraction = 2)
            BigDecimal maxPerTransaction,

            @NotNull @Positive @Digits(integer = 17, fraction = 2)
            BigDecimal maxDaily
    ) {

        @AssertTrue(message = "default per-transaction limit must not exceed the maximum per-transaction limit")
        public boolean isDefaultPerTransactionWithinMax() {
            return anyNull() || defaultPerTransaction.compareTo(maxPerTransaction) <= 0;
        }

        @AssertTrue(message = "default daily limit must not exceed the maximum daily limit")
        public boolean isDefaultDailyWithinMax() {
            return anyNull() || defaultDaily.compareTo(maxDaily) <= 0;
        }

        @AssertTrue(message = "default per-transaction limit must not exceed the default daily limit")
        public boolean isDefaultPerTransactionWithinDefaultDaily() {
            return anyNull() || defaultPerTransaction.compareTo(defaultDaily) <= 0;
        }

        @AssertTrue(message = "maximum per-transaction limit must not exceed the maximum daily limit")
        public boolean isMaxPerTransactionWithinMaxDaily() {
            return anyNull() || maxPerTransaction.compareTo(maxDaily) <= 0;
        }

        public CardSpendingLimits defaults() {
            return CardSpendingLimits.createNew(defaultPerTransaction, defaultDaily);
        }

        public CardSpendingLimits maximums() {
            return CardSpendingLimits.createNew(maxPerTransaction, maxDaily);
        }

        private boolean anyNull() {
            return defaultPerTransaction == null
                    || defaultDaily == null
                    || maxPerTransaction == null
                    || maxDaily == null;
        }
    }
}
