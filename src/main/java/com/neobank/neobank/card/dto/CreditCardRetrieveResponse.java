package com.neobank.neobank.card.dto;

import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.YearMonth;

public record CreditCardRetrieveResponse(
        String cardReference,
        String lastFourDigits,
        CardStatus status,
        YearMonth expirationYearMonth,
        String creditAccountNumber,
        BigDecimal creditLimit,
        BigDecimal balance,
        BigDecimal availableCredit,
        CurrencyCode currency,
        CardSpendingLimitsResponse spendingLimits
) {
}
