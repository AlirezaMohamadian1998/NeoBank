package com.neobank.neobank.card.issuing;

import java.time.YearMonth;

public interface CardIssuer {
    IssuedCard issue(YearMonth currentYearMonth);
}
