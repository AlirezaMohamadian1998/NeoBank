package com.neobank.neobank.transaction.withdrawal.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record WithdrawalResponse(
        String transactionReference,

        String entryReference,

        TransactionType transactionType,

        String accountNumber,

        BigDecimal amount,

        CurrencyCode currency,

        BigDecimal balanceAfter,

        String note,

        Instant createdAt
) {
}
