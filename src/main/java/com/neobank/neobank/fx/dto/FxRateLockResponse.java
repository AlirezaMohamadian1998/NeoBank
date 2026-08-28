package com.neobank.neobank.fx.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record FxRateLockResponse(
        String lockId,

        Instant expiresAt,

        CurrencyCode baseCurrency,

        Map<CurrencyCode, BigDecimal> rates
) {
}
