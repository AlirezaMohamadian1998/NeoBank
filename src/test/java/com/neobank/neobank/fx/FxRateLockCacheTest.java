package com.neobank.neobank.fx;

import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FxRateLockCacheTest {

    private final FxRateLockCache rateLockCache = new FxRateLockCache(new FxRateLockProperties(Duration.ofMinutes(2)));

    @Test
    void putAndGetReturnsStoredLockForSameCustomer() {
        String email = "customer@example.com";

        FxRateLockResponse response = new FxRateLockResponse(
                "123e4567-e89b-12d3-a456-426614174000",
                Instant.parse("2026-08-28T16:30:00Z"),
                CurrencyCode.USD,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.14"),
                        CurrencyCode.GBP, new BigDecimal("0.74"),
                        CurrencyCode.EUR, new BigDecimal("0.86")
                )
        );

        rateLockCache.put(email, response);

        assertThat(rateLockCache.get(email, response.lockId()))
                .contains(response);
    }

    @Test
    void getReturnsEmptyForDifferentCustomer() {
        String email = "customer@example.com";

        FxRateLockResponse response = new FxRateLockResponse(
                "123e4567-e89b-12d3-a456-426614174000",
                Instant.parse("2026-08-28T16:30:00Z"),
                CurrencyCode.USD,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.TRY, new BigDecimal("48.14"),
                        CurrencyCode.GBP, new BigDecimal("0.74"),
                        CurrencyCode.EUR, new BigDecimal("0.86")
                )
        );

        rateLockCache.put(email, response);

        assertThat(rateLockCache.get("another@example.com", response.lockId()))
                .isEmpty();
    }

    @Test
    void getReturnsEmptyWhenLockDoesNotExist() {
        assertThat(rateLockCache.get("customer@example.com", "non-existent-lock"))
                .isEmpty();
    }
}
