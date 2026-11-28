package com.neobank.neobank.card.credit.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

public record CreditCardPayoutResponse(
        String transactionReference,
        String cardReference,
        String creditAccountNumber,
        String destinationAccountNumber,
        BigDecimal debitedAmount,
        CurrencyCode debitedCurrency,
        BigDecimal creditedAmount,
        CurrencyCode creditedCurrency,
        BigDecimal destinationAccountBalanceAfter,
        BigDecimal creditCardBalanceAfter,
        Instant createdAt
) {
}
