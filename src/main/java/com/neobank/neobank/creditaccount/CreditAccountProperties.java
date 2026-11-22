package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        Map<CurrencyCode, @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal> defaultCreditLimits,

        @NotNull
        Map<CurrencyCode, @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) @Digits(integer = 1, fraction = 4) BigDecimal> defaultAnnualInterestRates,

        @NotNull
        Map<CurrencyCode, @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal> minimumPaymentFloors,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("1")
        @Digits(integer = 1, fraction = 4)
        BigDecimal minimumPaymentPercent,

        @Min(1)
        @Max(27)
        int paymentDueDays
) {

    public CreditAccountProperties {
        defaultCreditLimits = defaultCreditLimits == null ? null : Map.copyOf(defaultCreditLimits);
        defaultAnnualInterestRates = defaultAnnualInterestRates == null ? null : Map.copyOf(defaultAnnualInterestRates);
        minimumPaymentFloors = minimumPaymentFloors == null ? null : Map.copyOf(minimumPaymentFloors);
    }

    @AssertTrue(message = "credit-account.default-credit-limits must define a limit for every currency")
    public boolean isEveryCreditLimitConfigured() {
        return defaultCreditLimits == null || defaultCreditLimits.keySet().containsAll(EnumSet.allOf(CurrencyCode.class));
    }

    @AssertTrue(message = "credit-account.default-annual-interest-rates must define a rate for every currency")
    public boolean isEveryInterestRateConfigured() {
        return defaultAnnualInterestRates == null || defaultAnnualInterestRates.keySet().containsAll(EnumSet.allOf(CurrencyCode.class));
    }

    @AssertTrue(message = "credit-account.minimum-payment-floors must define a floor for every currency")
    public boolean isEveryMinimumPaymentFloorConfigured() {
        return minimumPaymentFloors == null || minimumPaymentFloors.keySet().containsAll(EnumSet.allOf(CurrencyCode.class));
    }

    public BigDecimal getDefaultCreditLimit(CurrencyCode currency) {
        return defaultCreditLimits.get(currency);
    }

    public BigDecimal getDefaultAnnualInterestRate(CurrencyCode currency) {
        return defaultAnnualInterestRates.get(currency);
    }

    public BigDecimal getMinimumPaymentFloor(CurrencyCode currency) {
        return minimumPaymentFloors.get(currency);
    }
}
