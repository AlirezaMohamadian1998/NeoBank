package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardSpendingLimitsResponse;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import com.neobank.neobank.creditaccount.CreditAccount;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;

@UtilityClass
public class CreditCardMapper {
    public static CreditCardIssueResponse toIssueResponse(CreditCard creditCard) {
        CreditAccount creditAccount = creditCard.getCreditAccount();

        return new CreditCardIssueResponse(
                creditCard.getCardReference(),
                creditCard.getExpirationYearMonth(),
                creditCard.getLastFourDigits(),
                creditAccount.getAccountNumber(),
                creditAccount.getCreditLimit(),
                creditAccount.getCurrency()
        );
    }

    public static CreditCardRetrieveResponse toRetrieveResponse(CreditCard creditCard, BigDecimal spentToday) {
        CreditAccount creditAccount = creditCard.getCreditAccount();
        CardSpendingLimits limits = creditCard.getSpendingLimits();

        return new CreditCardRetrieveResponse(
                creditCard.getCardReference(),
                creditCard.getLastFourDigits(),
                creditCard.getStatus(),
                creditCard.getExpirationYearMonth(),
                creditAccount.getAccountNumber(),
                creditAccount.getCreditLimit(),
                creditAccount.getLedgerAccount().getBalance(),
                creditAccount.getAvailableCredit(),
                creditAccount.getCurrency(),
                new CardSpendingLimitsResponse(
                        limits.getPerTransactionLimit(),
                        limits.getDailyLimit(),
                        spentToday,
                        creditAccount.getCurrency()
                )
        );
    }
}
