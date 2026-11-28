package com.neobank.neobank.card.credit.dto;

import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsResponse;
import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        BigDecimal annualInterestRate,
        LocalDate nextStatementDate,
        CurrencyCode currency,
        CardSpendingLimitsResponse spendingLimits
) {
}
