package com.neobank.neobank.transaction.transfer.dto;

import com.neobank.neobank.account.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        String transactionReference,

        TransactionType transactionType,

        String sourceAccountNumber,

        String destinationAccountNumber,

        BigDecimal amount,

        BigDecimal balanceAfter,

        CurrencyCode currency,

        String note,

        Instant createdAt
) {
}
