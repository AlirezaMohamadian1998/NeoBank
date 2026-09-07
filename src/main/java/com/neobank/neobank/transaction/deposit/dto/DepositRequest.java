package com.neobank.neobank.transaction.deposit.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record DepositRequest(

        @Positive(message = "Amount must be a positive number")
        @NotNull(message = "Amount cannot be null")
        @Digits(integer = 17, fraction = 2, message = "Amount must be a valid decimal number")
        BigDecimal amount,

        @Size(max = 255, message = "Note must not exceed 255 characters")
        String note,

        @NotNull(message = "Currency cannot be null")
        CurrencyCode requestedCurrency,

        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                message = "Lock ID must be a valid UUID format"
        )
        String lockId
) {
}
