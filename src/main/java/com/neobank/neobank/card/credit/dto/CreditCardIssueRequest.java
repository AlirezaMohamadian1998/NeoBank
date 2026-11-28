package com.neobank.neobank.card.credit.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import jakarta.validation.constraints.NotNull;

public record CreditCardIssueRequest(
        @NotNull(message = "Currency cannot be null")
        CurrencyCode currency
) {
}
