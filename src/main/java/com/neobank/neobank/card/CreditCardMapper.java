package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.card.dto.CardSpendingLimitsResponse;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardPayoutResponse;
import com.neobank.neobank.card.dto.CreditCardRepaymentResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import com.neobank.neobank.card.dto.CreditStatementResponse;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditStatement;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.LedgerEntry;
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
                creditAccount.getAnnualInterestRate(),
                creditAccount.getNextStatementDate(),
                creditAccount.getCurrency(),
                new CardSpendingLimitsResponse(
                        limits.getPerTransactionLimit(),
                        limits.getDailyLimit(),
                        spentToday,
                        creditAccount.getCurrency()
                )
        );
    }

    public static CreditStatementResponse toStatementResponse(CreditStatement statement) {
        return new CreditStatementResponse(
                statement.getStatementReference(),
                statement.getPeriodStart(),
                statement.getStatementDate(),
                statement.getDueDate(),
                statement.getOpeningBalance(),
                statement.getClosingBalance(),
                statement.getAverageDailyBalance(),
                statement.getInterestCharged(),
                statement.getMinimumPayment(),
                statement.getCreditAccount().getCurrency()
        );
    }

    public static CreditCardRepaymentResponse toRepaymentResponse(
            CreditCard creditCard,
            Account sourceAccount,
            BankTransaction transaction,
            LedgerEntry sourceEntry,
            LedgerEntry creditEntry
    ) {
        return new CreditCardRepaymentResponse(
                transaction.getReference(),
                creditCard.getCardReference(),
                creditCard.getCreditAccount().getAccountNumber(),
                sourceAccount.getAccountNumber(),
                sourceEntry.getAmount(),
                sourceEntry.getCurrency(),
                creditEntry.getAmount(),
                creditEntry.getCurrency(),
                sourceEntry.getBalanceAfter(),
                creditEntry.getBalanceAfter(),
                transaction.getCreatedAt()
        );
    }

    public static CreditCardPayoutResponse toPayoutResponse(
            CreditCard creditCard,
            Account destinationAccount,
            BankTransaction transaction,
            LedgerEntry cardEntry,
            LedgerEntry destinationEntry
    ) {
        return new CreditCardPayoutResponse(
                transaction.getReference(),
                creditCard.getCardReference(),
                creditCard.getCreditAccount().getAccountNumber(),
                destinationAccount.getAccountNumber(),
                cardEntry.getAmount(),
                cardEntry.getCurrency(),
                destinationEntry.getAmount(),
                destinationEntry.getCurrency(),
                destinationEntry.getBalanceAfter(),
                cardEntry.getBalanceAfter(),
                transaction.getCreatedAt()
        );
    }
}
