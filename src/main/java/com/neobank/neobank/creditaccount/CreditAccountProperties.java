package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;

@ConfigurationProperties(prefix = "credit-account")
@Validated
public record CreditAccountProperties(
        @NotNull
        Map<CurrencyCode, @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal> defaultCreditLimits
) {

    public CreditAccountProperties {
        defaultCreditLimits = defaultCreditLimits == null ? null : Map.copyOf(defaultCreditLimits);
    }

    @AssertTrue(message = "credit-account.default-credit-limits must define a limit for every currency")
    public boolean isEveryCurrencyConfigured() {
        return defaultCreditLimits == null || defaultCreditLimits.keySet().containsAll(EnumSet.allOf(CurrencyCode.class));
    }

    public BigDecimal getDefaultCreditLimit(CurrencyCode currency) {
        BigDecimal limit = defaultCreditLimits.get(currency);

        if(limit == null) {
            throw new IllegalStateException("No default credit limit configured for currency " + currency);
        }

        return limit;
    }
}
