package com.neobank.neobank.card.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

public record CreditCardClosingResponse(
        String cardReference,
        String creditAccountNumber,
        String payoffAccountNumber,
        String closingStatementReference,
        BigDecimal interestCharged,
        CurrencyCode currency,
        Instant closedAt
) {
}
