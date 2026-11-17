package com.neobank.neobank.transaction.history.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record CardPurchaseHistoryResponse(
        String accountNumber,

        String entryReference,

        TransactionType transactionType,

        EntryDirection direction,

        BigDecimal appliedAmount,

        CurrencyCode accountCurrency,

        BigDecimal requestedAmount,

        CurrencyCode requestedCurrency,

        BigDecimal balanceAfter,

        Instant createdAt,

        String merchantName,

        String cardReference,

        String cardLastFourDigits
) implements TransactionHistoryItem {
}
