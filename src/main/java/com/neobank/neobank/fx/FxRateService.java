package com.neobank.neobank.fx;

import com.neobank.neobank.fx.dto.SnapshotRateResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FxRateService {

    private final ProviderRateCache rateCache;

    public SnapshotRateResponse getLatestRates(CurrencyCode baseCurrency) {
        FxProviderRates providerRates = rateCache.getLatestRates();

        String id = providerRates.provider() + ":" + providerRates.providerTimestamp();

        Instant expiresAt = rateCache.getExpiration(providerRates);

        if(baseCurrency == providerRates.baseCurrency()) {
            return new SnapshotRateResponse(
                    id,
                    expiresAt,
                    baseCurrency,
                    providerRates.rates()
            );
        }

        BigDecimal requestedBaseCurrencyRate = providerRates.rates().get(baseCurrency);

        Map<CurrencyCode, BigDecimal> normalizedRates = new HashMap<>();

        normalizedRates.put(baseCurrency, BigDecimal.ONE);
        providerRates.rates().forEach((currency, rate) -> {
                    if(currency != baseCurrency) {
                        normalizedRates.put(currency, rate.divide(requestedBaseCurrencyRate, 8, RoundingMode.HALF_EVEN));
                    }
                }
        );

        return new SnapshotRateResponse(
                id,
                expiresAt,
                baseCurrency,
                Map.copyOf(normalizedRates)
        );
    }
}
