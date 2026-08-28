package com.neobank.neobank.fx;

import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FxRateService {

    private final ProviderRateCache providerRateCache;

    private final FxRateLockCache fxRateLockCache;

    private final Clock clock;

    private final FxRateLockProperties properties;

    public FxRateLockResponse createRateLock(CurrencyCode baseCurrency, String customerEmail) {
        FxProviderRates providerRates = providerRateCache.getLatestRates();

        String id = UUID.randomUUID().toString();

        Instant expiresAt = Instant.now(clock).plus(properties.duration());

        FxRateLockResponse response;

        if(baseCurrency == providerRates.baseCurrency()) {
            response = new FxRateLockResponse(
                    id,
                    expiresAt,
                    baseCurrency,
                    providerRates.rates()
            );
        } else {

            BigDecimal requestedBaseCurrencyRate = providerRates.rates().get(baseCurrency);

            Map<CurrencyCode, BigDecimal> normalizedRates = new HashMap<>();

            normalizedRates.put(baseCurrency, BigDecimal.ONE);
            providerRates.rates().forEach((currency, rate) -> {
                        if(currency != baseCurrency) {
                            normalizedRates.put(currency, rate.divide(requestedBaseCurrencyRate, 8, RoundingMode.HALF_EVEN));
                        }
                    }
            );

            response = new FxRateLockResponse(
                    id,
                    expiresAt,
                    baseCurrency,
                    Map.copyOf(normalizedRates)
            );
        }

        fxRateLockCache.put(customerEmail, response);
        return response;
    }

    public FxRateLockResponse getCachedRate(String customerEmail, String lockId) {
        return fxRateLockCache.get(customerEmail, lockId)
                .orElseThrow(() -> new FxRateLockUnavailableException("Invalid or expired FX rate lock"));
    }
}
