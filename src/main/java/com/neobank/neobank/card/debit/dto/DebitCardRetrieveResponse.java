package com.neobank.neobank.card.debit.dto;

import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsResponse;

import java.time.YearMonth;

public record DebitCardRetrieveResponse(
        String cardReference,
        String lastFourDigits,
        CardStatus status,
        YearMonth expirationYearMonth,
        String fundingAccountNumber,
        CardSpendingLimitsResponse spendingLimits
) {
}
