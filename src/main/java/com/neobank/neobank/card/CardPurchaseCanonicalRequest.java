package com.neobank.neobank.card;

import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CardPurchaseCanonicalRequest(
        TransactionType transactionType,
        String cardReference,
        String merchantAccountNumber,
        String merchantName,
        BigDecimal amount,
        CurrencyCode currency,
        String note
) {
    public CardPurchaseCanonicalRequest {
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        note = note == null ? "" : note.trim();
    }
}
