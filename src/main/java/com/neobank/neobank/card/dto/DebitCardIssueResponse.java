package com.neobank.neobank.card.dto;

import java.time.YearMonth;

public record DebitCardIssueResponse(
        String cardReference,
        YearMonth expirationYearMonth,
        String fundingAccountNumber,
        String lastFourDigits
) {
}
