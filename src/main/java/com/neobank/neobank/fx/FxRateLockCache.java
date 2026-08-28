package com.neobank.neobank.fx;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class FxRateLockCache {

    private record LockKey(String customerEmail, String lockId) {
    }

    private final Cache<LockKey, FxRateLockResponse> cache;

    public FxRateLockCache(FxRateLockProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.duration())
                .maximumSize(10000)
                .build();

    }

    public void put(String customerEmail, FxRateLockResponse response) {
        cache.put(new LockKey(customerEmail, response.lockId()), response);
    }

    public Optional<FxRateLockResponse> get(String customerEmail, String lockId) {
        return Optional.ofNullable(cache.getIfPresent(new LockKey(customerEmail, lockId)));
    }
}
