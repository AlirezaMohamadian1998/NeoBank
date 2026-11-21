package com.neobank.neobank.card.issuing;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCardIssuerTest {

    private final LocalCardIssuer cardIssuer = new LocalCardIssuer();

    @Test
    void issueReturnsFourNumericDigitsAndSetsExpirationFiveYearsAfterCurrentMonth() {
        YearMonth currentYearMonth = YearMonth.of(2026, 9);

        IssuedCard issuedCard = cardIssuer.issue(currentYearMonth);

        assertThat(issuedCard.lastFourDigits())
                .matches("\\d{4}");

        assertThat(issuedCard.expirationYearMonth())
                .isEqualTo(currentYearMonth.plusYears(5));
    }
}
