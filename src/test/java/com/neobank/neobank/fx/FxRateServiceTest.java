package com.neobank.neobank.fx;

import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    @Mock
    private ProviderRateCache rateCache;

    @Mock
    private FxRateLockCache rateLockCache;

    @Mock
    private FxRateLockProperties properties;

    private FxRateService fxRateService;

    private FxProviderRates providerRates;

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @BeforeEach
    void setUp() {
        fxRateService = new FxRateService(
                rateCache,
                rateLockCache,
                Clock.fixed(NOW, ZoneOffset.UTC),
                properties

        );
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
        String email = "example.com";

        given(rateCache.getLatestRates())
                .willReturn(providerRates);

        given(properties.duration())
                .willReturn(Duration.ofMinutes(5));

        FxRateLockResponse response = fxRateService.createRateLock(CurrencyCode.USD, email);

        assertThat(response.rates())
                .isEqualTo(providerRates.rates());

        assertThat(response.baseCurrency())
                .isSameAs(providerRates.baseCurrency());

        assertThat(UUID.fromString(response.lockId()))
                .isNotNull();

        assertThat(response.expiresAt())
                .isEqualTo(NOW.plus(properties.duration()));

        verify(rateLockCache).put(email, response);

        verify(rateCache, times(1))
                .getLatestRates();
    }

    @Test
    void nonUsdBaseReturnsNormalizedRates() {
        String email = "example.com";

        given(rateCache.getLatestRates())
                .willReturn(providerRates);

        given(properties.duration())
                .willReturn(Duration.ofMinutes(5));

        FxRateLockResponse response = fxRateService.createRateLock(CurrencyCode.TRY, email);

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

        assertThat(UUID.fromString(response.lockId()))
                .isNotNull();

        assertThat(response.expiresAt())
                .isEqualTo(NOW.plus(properties.duration()));

        verify(rateCache, times(1))
                .getLatestRates();
    }

    @Test
    void getCachedRateReturnsAvailableLock() {
        String email = "customer@example.com";
        String lockId = "123e4567-e89b-12d3-a456-426614174000";

        FxRateLockResponse response = new FxRateLockResponse(
                lockId,
                Instant.parse("2026-08-28T16:30:00Z"),
                CurrencyCode.USD,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.14"),
                        CurrencyCode.GBP, new BigDecimal("0.74"),
                        CurrencyCode.EUR, new BigDecimal("0.86")
                )
        );

        given(rateLockCache.get(email, lockId))
                .willReturn(Optional.of(response));

        assertThat(fxRateService.getCachedRate(email, lockId))
                .isEqualTo(response);
    }

    @Test
    void getCachedRateThrowsWhenLockIsUnavailable() {
        String email = "customer@example.com";
        String lockId = "123e4567-e89b-12d3-a456-426614174000";

        given(rateLockCache.get(email, lockId))
                .willReturn(Optional.empty());


        assertThatThrownBy(() -> fxRateService.getCachedRate(email, lockId))
                .isInstanceOf(FxRateLockUnavailableException.class)
                .hasMessage("Invalid or expired FX rate lock");
    }
}
