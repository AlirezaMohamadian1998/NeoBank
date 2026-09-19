package com.neobank.neobank.transaction.history.dto;

import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionHistoryFilter(

        @Pattern(regexp = "\\d{14}", message = "Account number must be exactly 14 digits")
        String accountNumber,

        TransactionType type,

        CurrencyCode requestedCurrency,

        @PastOrPresent
        Instant dateFrom,

        @PastOrPresent
        Instant dateTo,

        @Positive
        BigDecimal appliedMin,

        @Positive
        BigDecimal appliedMax
) {
    @AssertTrue(message = "Maximum amount must be greater than or equal to minimum amount")
    public boolean isAmountRangeValid() {
        return appliedMin == null || appliedMax == null || appliedMin.compareTo(appliedMax) <= 0;
    }

    @AssertTrue(message = "dateFrom must be before or equal to dateTo")
    public boolean isDateRangeValid() {
        return dateFrom == null || dateTo == null || !dateFrom.isAfter(dateTo);
    }
}
