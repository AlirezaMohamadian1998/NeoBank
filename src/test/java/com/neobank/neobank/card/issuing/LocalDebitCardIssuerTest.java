package com.neobank.neobank.card.issuing;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDebitCardIssuerTest {

    private final LocalDebitCardIssuer debitCardIssuer = new LocalDebitCardIssuer();

    @Test
    void issueReturnsFourNumericDigitsAndSetsExpirationFiveYearsAfterCurrentMonth() {
        YearMonth currentYearMonth = YearMonth.of(2026, 9);

        IssuedDebitCard issuedDebitCard = debitCardIssuer.issue(currentYearMonth);

        assertThat(issuedDebitCard.lastFourDigits())
                .matches("\\d{4}");

        assertThat(issuedDebitCard.expirationYearMonth())
                .isEqualTo(currentYearMonth.plusYears(5));
    }
}
