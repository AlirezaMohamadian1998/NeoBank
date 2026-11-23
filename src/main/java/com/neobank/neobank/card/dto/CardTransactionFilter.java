package com.neobank.neobank.card.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.PastOrPresent;

import java.time.Instant;

public record CardTransactionFilter(

        @PastOrPresent
        Instant dateFrom,

        @PastOrPresent
        Instant dateTo
) {
    @AssertTrue(message = "dateFrom must be before or equal to dateTo")
    public boolean isDateRangeValid() {
        return dateFrom == null || dateTo == null || !dateFrom.isAfter(dateTo);
    }
}
