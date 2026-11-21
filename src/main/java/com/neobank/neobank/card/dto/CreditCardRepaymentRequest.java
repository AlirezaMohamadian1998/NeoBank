package com.neobank.neobank.card.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreditCardRepaymentRequest(
        @NotNull(message = "Source account number cannot be null")
        @Pattern(regexp = "\\d{14}", message = "Account number must be 14 digits")
        String sourceAccountNumber,

        @Positive(message = "Amount must be a positive number")
        @NotNull(message = "Amount cannot be null")
        @Digits(integer = 17, fraction = 2, message = "Amount must be a valid decimal number")
        BigDecimal amount,

        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                message = "Lock ID must be a valid UUID format"
        )
        String lockId
) {
}
