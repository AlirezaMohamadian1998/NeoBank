package com.neobank.neobank.account.dto;

import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.account.CurrencyCode;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        String accountNumber,
        String name,
        AccountType accountType,
        CurrencyCode currency,
        BigDecimal balance,
        Instant createdAt
) {
}
