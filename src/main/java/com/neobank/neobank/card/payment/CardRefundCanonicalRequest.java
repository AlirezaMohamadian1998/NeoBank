package com.neobank.neobank.card.payment;

import com.neobank.neobank.transaction.TransactionType;

public record CardRefundCanonicalRequest(
        TransactionType transactionType,
        String merchantAccountNumber,
        String purchaseReference
) {
}
