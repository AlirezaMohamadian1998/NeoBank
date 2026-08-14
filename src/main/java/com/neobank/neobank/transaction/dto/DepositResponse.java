package com.neobank.neobank.transaction.dto;

import com.neobank.neobank.account.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record DepositResponse(
        String transactionReference,

        TransactionType transactionType,

        String accountNumber,

        BigDecimal amount,

        CurrencyCode currency,

        BigDecimal balanceAfter,

        String note,

        Instant createdAt
) {
}
