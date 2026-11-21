package com.neobank.neobank.card.issuing;

import java.time.YearMonth;

public record IssuedCard(
        String lastFourDigits,
        YearMonth expirationYearMonth
) {
}
