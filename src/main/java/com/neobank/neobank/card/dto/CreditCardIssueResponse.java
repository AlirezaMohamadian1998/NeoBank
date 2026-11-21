package com.neobank.neobank.card.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.YearMonth;

public record CreditCardIssueResponse(
        String cardReference,
        YearMonth expirationYearMonth,
        String lastFourDigits,
        String creditAccountNumber,
        BigDecimal creditLimit,
        CurrencyCode currency
) {
}
