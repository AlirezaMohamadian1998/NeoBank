package com.neobank.neobank.card.debit;

import com.neobank.neobank.card.debit.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.debit.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.card.limit.CardSpendingLimits;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsResponse;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;

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

    public static DebitCardRetrieveResponse toRetrieveDebitCardResponse(DebitCard debitCard, BigDecimal spentToday) {
        CardSpendingLimits limits = debitCard.getSpendingLimits();

        return new DebitCardRetrieveResponse(
                debitCard.getCardReference(),
                debitCard.getLastFourDigits(),
                debitCard.getStatus(),
                debitCard.getExpirationYearMonth(),
                debitCard.getFundingAccount().getAccountNumber(),
                new CardSpendingLimitsResponse(
                        limits.getPerTransactionLimit(),
                        limits.getDailyLimit(),
                        spentToday,
                        debitCard.getFundingAccount().getCurrency()
                )
        );
    }
}
