package com.neobank.neobank.card.issuing;

import java.time.YearMonth;

public record IssuedDebitCard(
        String lastFourDigits,
        YearMonth expirationYearMonth
) {
}
