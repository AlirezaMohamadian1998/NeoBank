package com.neobank.neobank.card.issuing;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.YearMonth;

@Component
public class LocalCardIssuer implements CardIssuer {

    private static final int CARD_VALIDITY_YEARS = 5;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public IssuedCard issue(YearMonth currentYearMonth) {
        String lastFourDigits = String.format(
                "%04d",
                secureRandom.nextInt(10000)
        );

        return new IssuedCard(
                lastFourDigits,
                currentYearMonth.plusYears(CARD_VALIDITY_YEARS)
        );
    }
}
