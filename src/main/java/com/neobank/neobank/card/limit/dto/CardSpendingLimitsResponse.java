package com.neobank.neobank.card.limit.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;

public record CardSpendingLimitsResponse(
        BigDecimal perTransactionLimit,
        BigDecimal dailyLimit,
        BigDecimal spentToday,
        CurrencyCode currency
) {
}
