package com.neobank.neobank.card.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreditStatementResponse(
        String statementReference,
        LocalDate periodStart,
        LocalDate statementDate,
        LocalDate dueDate,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal averageDailyBalance,
        BigDecimal interestCharged,
        BigDecimal minimumPayment,
        CurrencyCode currency
) {
}
