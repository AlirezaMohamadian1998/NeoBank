package com.neobank.neobank.fx;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class ProviderRateCache {

    private static final String CACHE_KEY = "LATEST_RATES";

    private final LoadingCache<String, FxProviderRates> cache;

    private final static Duration PROVIDER_UPDATE_INTERVAL = Duration.ofHours(1);

    public ProviderRateCache(
            @Qualifier("openExchangeRates") FxRateProvider rateProvider,
            Clock clock
    ) {
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, FxProviderRates>() {
                    @Override
                    public long expireAfterCreate(String key, FxProviderRates value, long currentTime) {
                        Duration remaining = Duration.between(Instant.now(clock), getExpiration(value));

                        return remaining.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, FxProviderRates value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(String key, FxProviderRates value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(1)
                .build(key -> {
                    FxProviderRates rates = rateProvider.fetchLatestRates();

                    if(!getExpiration(rates).isAfter(Instant.now(clock))) {
                        throw new FxProviderUnavailableException("Provider returned stale FX rates");
                    }

                    return rates;
                });
    }

    public FxProviderRates getLatestRates() {
        return cache.get(CACHE_KEY);
    }

    private Instant getExpiration(@NonNull FxProviderRates rates) {
        return rates.providerTimestamp()
                .plus(PROVIDER_UPDATE_INTERVAL);
    }
}
