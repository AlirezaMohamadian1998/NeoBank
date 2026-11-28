package com.neobank.neobank.card.limit.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CardSpendingLimitsUpdateRequest(
        @NotNull(message = "Per-transaction limit cannot be null")
        @Positive(message = "Per-transaction limit must be a positive number")
        @Digits(integer = 17, fraction = 2, message = "Per-transaction limit must be a valid decimal number")
        BigDecimal perTransactionLimit,

        @NotNull(message = "Daily limit cannot be null")
        @Positive(message = "Daily limit must be a positive number")
        @Digits(integer = 17, fraction = 2, message = "Daily limit must be a valid decimal number")
        BigDecimal dailyLimit
) {
}
