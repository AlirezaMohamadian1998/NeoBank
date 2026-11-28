package com.neobank.neobank.fx;

import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxProviderRatesTest {

    private static final String PROVIDER = "openexchangerates";
    private static final Instant PROVIDER_TIMESTAMP = Instant.parse("2026-08-26T08:00:00Z");

    @Test
    void validRatesAreAcceptedAndDefensivelyCopied() {
        Map<CurrencyCode, BigDecimal> rates = validRates();

        FxProviderRates providerRates = new FxProviderRates(
                PROVIDER,
                CurrencyCode.USD,
                rates,
                PROVIDER_TIMESTAMP
        );

        rates.put(CurrencyCode.EUR, new BigDecimal("9.99"));

        assertThat(providerRates.rates().get(CurrencyCode.EUR))
                .isEqualByComparingTo("0.86");

        assertThatThrownBy(() -> providerRates.rates().put(CurrencyCode.EUR, BigDecimal.ONE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingSupportedCurrencyIsRejected() {
        Map<CurrencyCode, BigDecimal> rates = validRates();
        rates.remove(CurrencyCode.GBP);

        assertThatThrownBy(() -> new FxProviderRates(
                PROVIDER,
                CurrencyCode.USD,
                rates,
                PROVIDER_TIMESTAMP
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rates must contain every NeoBank-supported currency");
    }

    @ParameterizedTest
    @CsvSource({
            "EUR, 0, Rate for EUR must be positive",
            "USD, 1.01, Base currency rate must equal 1"
    })
    void invalidRateIsRejected(CurrencyCode currency, BigDecimal rate, String message) {
        Map<CurrencyCode, BigDecimal> rates = validRates();
        rates.put(currency, rate);

        assertThatThrownBy(() -> new FxProviderRates(
                PROVIDER,
                CurrencyCode.USD,
                rates,
                PROVIDER_TIMESTAMP
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void invalidProviderTimestampIsRejected() {
        assertThatThrownBy(() -> new FxProviderRates(
                PROVIDER,
                CurrencyCode.USD,
                validRates(),
                Instant.EPOCH
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerTimestamp must be after the Unix epoch");
    }

    private Map<CurrencyCode, BigDecimal> validRates() {
        Map<CurrencyCode, BigDecimal> rates = new EnumMap<>(CurrencyCode.class);
        rates.put(CurrencyCode.USD, BigDecimal.ONE);
        rates.put(CurrencyCode.EUR, new BigDecimal("0.86"));
        rates.put(CurrencyCode.GBP, new BigDecimal("0.74"));
        rates.put(CurrencyCode.TRY, new BigDecimal("48.00"));
        return rates;
    }
}
