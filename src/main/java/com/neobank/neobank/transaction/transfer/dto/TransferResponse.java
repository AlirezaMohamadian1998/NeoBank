package com.neobank.neobank.transaction.transfer.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        String transactionReference,

        String sourceEntryReference,

        TransactionType transactionType,

        String sourceAccountNumber,

        String destinationAccountNumber,

        BigDecimal amount,

        BigDecimal sourceBalanceAfter,

        CurrencyCode currency,

        String note,

        Instant createdAt
) {
}
