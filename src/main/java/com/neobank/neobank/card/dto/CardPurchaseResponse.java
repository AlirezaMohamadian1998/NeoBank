package com.neobank.neobank.card.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

public record CardPurchaseResponse(
        String transactionReference,
        BigDecimal requestedAmount,
        BigDecimal debitedAmount,
        CurrencyCode requestedCurrency,
        CurrencyCode debitedCurrency,
        BigDecimal balanceAfter,
        String merchantName,
        String cardLastFourDigits,
        String cardReference,
        String fundingAccountNumber,
        Instant createdAt
) {}
