package com.neobank.neobank.transaction.transfer.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record TransferRequest(

        @Positive(message = "Amount must be a positive number")
        @NotNull(message = "Amount cannot be null")
        @Digits(integer = 17, fraction = 2, message = "Amount must be a valid decimal number")
        BigDecimal amount,

        @Pattern(regexp = "\\d{14}", message = "Account number must be exactly 14 digits")
        @NotBlank(message = "Destination account number cannot be blank")
        String destinationAccountNumber,

        @Size(max = 255, message = "Note must not exceed 255 characters")
        String note,

        @NotNull(message = "Currency cannot be null")
        CurrencyCode currency,

        @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
                message = "Lock ID must be a valid UUID format"
        )
        String lockId
) {
}
