package com.neobank.neobank.card;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class PayoffQuoteCache {

    private record QuoteKey(String customerEmail, String cardReference, String quoteId) {
    }

    private final Cache<QuoteKey, PayoffQuote> cache;

    public PayoffQuoteCache(Clock clock) {
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<QuoteKey, PayoffQuote>() {
                    @Override
                    public long expireAfterCreate(QuoteKey key, PayoffQuote quote, long currentTime) {
                        Duration remaining = Duration.between(Instant.now(clock), quote.expiresAt());

                        return remaining.toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(QuoteKey key, PayoffQuote quote, long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(QuoteKey key, PayoffQuote quote, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(10000)
                .build();
    }

    public void put(String customerEmail, PayoffQuote quote) {
        cache.put(new QuoteKey(customerEmail, quote.cardReference(), quote.quoteId()), quote);
    }

    public Optional<PayoffQuote> get(String customerEmail, String cardReference, String quoteId) {
        return Optional.ofNullable(cache.getIfPresent(new QuoteKey(customerEmail, cardReference, quoteId)));
    }
}
