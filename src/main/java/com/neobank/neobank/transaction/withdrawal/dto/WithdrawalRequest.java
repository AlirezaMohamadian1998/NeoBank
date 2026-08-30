package com.neobank.neobank.transaction.withdrawal.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record WithdrawalRequest(
        @Positive(message = "Amount must be a positive number")
        @NotNull(message = "Amount cannot be null")
        @Digits(integer = 17, fraction = 2, message = "Amount must be a valid decimal number")
        BigDecimal amount,

        @Size(max = 255, message = "Note must not exceed 255 characters")
        String note,

        @NotNull(message = "Currency must not be null")
        CurrencyCode requestedCurrency,

        @Pattern(regexp = "^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[ab89][a-f0-9]{3}-[a-f0-9]{12}$",
                message = "Lock ID must be a valid UUID format"
        )
        String lockId
) {
}
