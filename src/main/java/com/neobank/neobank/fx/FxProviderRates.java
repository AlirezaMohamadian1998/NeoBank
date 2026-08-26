package com.neobank.neobank.fx;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record FxProviderRates(

        @NotBlank
        String provider,

        @NotNull
        CurrencyCode baseCurrency,

        @NotNull
        Map<CurrencyCode, BigDecimal> rates,

        @NotNull
        Instant providerTimestamp
) {
    public FxProviderRates {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }

        if (baseCurrency == null) {
            throw new IllegalArgumentException("baseCurrency must not be null");
        }

        if (rates == null) {
            throw new IllegalArgumentException("rates must not be null");
        }

        if (!rates.keySet().equals(Set.of(CurrencyCode.values()))) {
            throw new IllegalArgumentException("rates must contain every NeoBank-supported currency");
        }

        rates.forEach((currency, rate) -> {
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("Rate for " + currency + " must be positive");
            }
        });

        BigDecimal baseRate = rates.get(baseCurrency);

        if (baseRate == null || baseRate.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("Base currency rate must equal 1");
        }

        if (providerTimestamp == null || !providerTimestamp.isAfter(Instant.EPOCH)) {
            throw new IllegalArgumentException("providerTimestamp must be after the Unix epoch");
        }

        rates = Map.copyOf(rates);
    }
}
