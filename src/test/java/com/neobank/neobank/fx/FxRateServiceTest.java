package com.neobank.neobank.fx;

import com.neobank.neobank.fx.dto.SnapshotRateResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    @Mock
    private ProviderRateCache rateCache;

    @InjectMocks
    private FxRateService fxRateService;

    private FxProviderRates providerRates;

    @BeforeEach
    void setUp() {
        Map<CurrencyCode, BigDecimal> rates = Map.of(
                CurrencyCode.USD, BigDecimal.ONE,
                CurrencyCode.TRY, new BigDecimal("48.14"),
                CurrencyCode.GBP, new BigDecimal("0.74"),
                CurrencyCode.EUR, new BigDecimal("0.86")
        );
        providerRates = new FxProviderRates(
                "example",
                CurrencyCode.USD,
                rates,
                Instant.parse("2026-08-27T12:00:00Z")
        );
    }

    @Test
    void usdBaseReturnsProviderRatesUnchanged() {
        Instant expiresAt = Instant.parse("2026-08-27T13:00:00Z");

        given(rateCache.getLatestRates())
                .willReturn(providerRates);

        given(rateCache.getExpiration(providerRates))
                .willReturn(expiresAt);

        SnapshotRateResponse response = fxRateService.getLatestRates(CurrencyCode.USD);

        assertThat(response.rates())
                .isEqualTo(providerRates.rates());

        assertThat(response.baseCurrency())
                .isSameAs(providerRates.baseCurrency());

        assertThat(response.rateId())
                .isEqualTo(providerRates.provider() + ":" + providerRates.providerTimestamp());

        assertThat(response.expiresAt())
                .isEqualTo(expiresAt);

        verify(rateCache, times(1))
                .getLatestRates();

        verify(rateCache, times(1))
                .getExpiration(providerRates);
    }

    @Test
    void nonUsdBaseReturnsNormalizedRates() {
        Instant expiresAt = Instant.parse("2026-08-27T13:00:00Z");

        given(rateCache.getLatestRates())
                .willReturn(providerRates);

        given(rateCache.getExpiration(providerRates))
                .willReturn(expiresAt);

        SnapshotRateResponse response = fxRateService.getLatestRates(CurrencyCode.TRY);

        Map<CurrencyCode, BigDecimal> normalizedRates = Map.of(
                CurrencyCode.TRY, BigDecimal.ONE,
                CurrencyCode.USD, new BigDecimal("0.02077275"),
                CurrencyCode.GBP, new BigDecimal("0.01537183"),
                CurrencyCode.EUR, new BigDecimal("0.01786456")
        );

        assertThat(response.rates())
                .isEqualTo(normalizedRates);

        assertThat(response.baseCurrency())
                .isSameAs(CurrencyCode.TRY);

        assertThat(response.rateId())
                .isEqualTo(providerRates.provider() + ":" + providerRates.providerTimestamp());

        assertThat(response.expiresAt())
                .isEqualTo(expiresAt);

        verify(rateCache, times(1))
                .getLatestRates();

        verify(rateCache, times(1))
                .getExpiration(providerRates);
    }
}
