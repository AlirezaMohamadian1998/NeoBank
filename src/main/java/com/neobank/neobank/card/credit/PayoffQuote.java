package com.neobank.neobank.card.credit;

import java.math.BigDecimal;
import java.time.Instant;

public record PayoffQuote(
        String quoteId,
        String cardReference,
        BigDecimal payoffAmount,
        Long ledgerVersion,
        Instant expiresAt
) {
}
