package com.neobank.neobank.card.credit.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

public record PayoffQuoteResponse(
        String quoteId,
        String cardReference,
        CurrencyCode currency,
        BigDecimal balance,
        BigDecimal accruedInterest,
        BigDecimal payoffAmount,
        Instant expiresAt
) {
}
