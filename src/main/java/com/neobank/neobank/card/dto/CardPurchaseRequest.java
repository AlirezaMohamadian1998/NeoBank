package com.neobank.neobank.card.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CardPurchaseRequest(
        @Nullable
        @Pattern(regexp = "\\d{14}", message = "Account number must be 14 digits")
        String merchantAccountNumber,

        @NotNull(message = "Currency cannot be null")
        CurrencyCode currency,

        @Positive(message = "Amount must be a positive number")
        @NotNull(message = "Amount cannot be null")
        @Digits(integer = 17, fraction = 2, message = "Amount must be a valid decimal number")
        BigDecimal amount,

        @Pattern(regexp = "(?s).*\\S.*", message = "Note must not be blank")
        @Size(max = 255, message = "Note must not exceed 255 characters")
        String note,

        @Size(max = 128, message = "Merchant name must not exceed 128 characters")
        @NotBlank(message = "Merchant name must not be null or blank")
        String merchantName
) {
}
