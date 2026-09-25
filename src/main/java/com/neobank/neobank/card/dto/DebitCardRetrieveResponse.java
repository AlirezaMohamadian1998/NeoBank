package com.neobank.neobank.card.dto;

import com.neobank.neobank.card.CardStatus;

import java.time.YearMonth;

public record DebitCardRetrieveResponse(
        String cardReference,
        String lastFourDigits,
        CardStatus status,
        YearMonth expirationYearMonth,
        String fundingAccountNumber
) {
}
