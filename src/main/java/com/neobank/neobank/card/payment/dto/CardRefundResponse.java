package com.neobank.neobank.card.payment.dto;

import com.neobank.neobank.shared.money.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

public record CardRefundResponse(
        String refundReference,
        String purchaseReference,
        BigDecimal requestedAmount,
        CurrencyCode requestedCurrency,
        BigDecimal debitedAmount,
        CurrencyCode debitedCurrency,
        BigDecimal balanceAfter,
        String merchantName,
        String merchantAccountNumber,
        String cardLastFourDigits,
        Instant createdAt
) {}
