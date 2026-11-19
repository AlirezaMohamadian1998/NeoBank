package com.neobank.neobank.transaction.history.dto;

public sealed interface TransactionHistoryItem
        permits TransactionHistoryResponse, CardPurchaseHistoryResponse, CardRefundHistoryResponse {
}
