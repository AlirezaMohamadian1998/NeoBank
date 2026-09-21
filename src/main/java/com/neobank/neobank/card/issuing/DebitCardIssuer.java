package com.neobank.neobank.card.issuing;

import java.time.YearMonth;

public interface DebitCardIssuer {
    IssuedDebitCard issue(YearMonth currentYearMonth);
}
