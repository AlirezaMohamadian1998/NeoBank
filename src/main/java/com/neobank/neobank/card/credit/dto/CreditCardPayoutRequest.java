package com.neobank.neobank.card.credit.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreditCardPayoutRequest(
        @NotNull(message = "Destination account number cannot be null")
        @Pattern(regexp = "\\d{14}", message = "Account number must be 14 digits")
        String destinationAccountNumber,

        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                message = "Lock ID must be a valid UUID format"
        )
        String lockId
) {
}
