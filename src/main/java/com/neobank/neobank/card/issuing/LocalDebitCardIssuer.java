package com.neobank.neobank.card.issuing;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.YearMonth;

@Component
public class LocalDebitCardIssuer implements DebitCardIssuer {

    private static final int CARD_VALIDITY_YEARS = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public IssuedDebitCard issue(YearMonth currentYearMonth) {
        String lastFourDigits = String.format(
                "%04d",
                secureRandom.nextInt(10000)
        );

        return new IssuedDebitCard(
                lastFourDigits,
                currentYearMonth.plusYears(CARD_VALIDITY_YEARS)
        );
    }
}
