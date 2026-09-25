package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DebitCardMapper {
    public static DebitCardIssueResponse toIssueDebitCardResponse(DebitCard debitCard, String fundingAccountNumber) {
        return new DebitCardIssueResponse(
                debitCard.getCardReference(),
                debitCard.getExpirationYearMonth(),
                fundingAccountNumber,
                debitCard.getLastFourDigits()
        );
    }
}
