package com.neobank.neobank.card.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreditCardClosingRequest(
        @NotNull(message = "Quote ID cannot be null")
        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                message = "Quote ID must be a valid UUID format"
        )
        String quoteId,

        @NotNull(message = "Payoff account number cannot be null")
        @Pattern(regexp = "\\d{14}", message = "Account number must be 14 digits")
        String payoffAccountNumber,

        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                message = "Lock ID must be a valid UUID format"
        )
        String lockId
) {
}
