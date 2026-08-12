package com.neobank.neobank.account.dto;

import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.account.CurrencyCode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,

        @NotNull(message = "Account type cannot be null")
        AccountType accountType,

        @NotNull(message = "Currency cannot be null")
        CurrencyCode currency
) {
}
