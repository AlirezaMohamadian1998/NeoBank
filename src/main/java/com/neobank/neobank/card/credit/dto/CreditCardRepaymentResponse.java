package com.neobank.neobank.card.credit.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

public record CreditCardRepaymentResponse(
        String transactionReference,
        String cardReference,
        String creditAccountNumber,
        String sourceAccountNumber,
        BigDecimal debitedAmount,
        CurrencyCode debitedCurrency,
        BigDecimal creditedAmount,
        CurrencyCode creditedCurrency,
        BigDecimal sourceAccountBalanceAfter,
        BigDecimal creditCardBalanceAfter,
        Instant createdAt
) {
}
