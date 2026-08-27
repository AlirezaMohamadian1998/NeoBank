package com.neobank.neobank.fx;

import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProviderRateCacheTest {

    @Mock
    private FxRateProvider rateProvider;

    private ProviderRateCache providerRateCache;

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), Clock.systemUTC().getZone());

    private Map<CurrencyCode, BigDecimal> rates;

    @BeforeEach
    void setUp() {
        providerRateCache = new ProviderRateCache(rateProvider, clock);
        rates = Map.of(
                CurrencyCode.USD, BigDecimal.ONE,
                CurrencyCode.TRY, new BigDecimal("48.14"),
                CurrencyCode.GBP, new BigDecimal("0.74"),
                CurrencyCode.EUR, new BigDecimal("0.86")
        );
    }

    @Test
    void repeatedAccessUsesCachedValue() {
        FxProviderRates providerRates = new FxProviderRates(
                "openExchangeRates",
                CurrencyCode.USD,
                rates,
                Instant.parse("2026-08-27T12:00:00Z")
        );

        given(rateProvider.fetchLatestRates())
                .willReturn(providerRates);

        FxProviderRates response1 = providerRateCache.getLatestRates();
        FxProviderRates response2 = providerRateCache.getLatestRates();

        assertThat(response1)
                .isEqualTo(providerRates);

        assertThat(response2)
                .isEqualTo(providerRates);

        verify(rateProvider, times(1))
                .fetchLatestRates();
    }

    @Test
    void staleProviderRatesAreRejectedAndNotCached() {
        FxProviderRates providerRates = new FxProviderRates(
                "openExchangeRates",
                CurrencyCode.USD,
                rates,
                Instant.parse("2026-08-27T10:00:00Z")
        );

        given(rateProvider.fetchLatestRates())
                .willReturn(providerRates);

        assertThatThrownBy(() -> providerRateCache.getLatestRates())
                .isInstanceOf(FxProviderUnavailableException.class)
                .hasMessage("Provider returned stale FX rates");

        assertThatThrownBy(() -> providerRateCache.getLatestRates())
                .isInstanceOf(FxProviderUnavailableException.class)
                .hasMessage("Provider returned stale FX rates");

        verify(rateProvider, times(2))
                .fetchLatestRates();
    }

    @Test
    void providerFailureIsNotCached() {
        FxProviderRates providerRates = new FxProviderRates(
                "openExchangeRates",
                CurrencyCode.USD,
                rates,
                Instant.parse("2026-08-27T11:20:00Z")
        );

        given(rateProvider.fetchLatestRates())
                .willThrow(new FxProviderUnavailableException("Provider unavailable"))
                .willReturn(providerRates);

        assertThatThrownBy(() -> providerRateCache.getLatestRates())
                .isInstanceOf(FxProviderUnavailableException.class)
                .hasMessage("Provider unavailable");

        FxProviderRates response = providerRateCache.getLatestRates();

        assertThat(response)
                .isEqualTo(providerRates);

        verify(rateProvider, times(2))
                .fetchLatestRates();
    }
}
