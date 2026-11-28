package com.neobank.neobank.creditaccount;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditStatementTest {

    private static final String STATEMENT_REFERENCE = "a".repeat(32);

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 15);

    private static final LocalDate STATEMENT_DATE = LocalDate.of(2026, 2, 15);

    private static final LocalDate DUE_DATE = LocalDate.of(2026, 3, 12);

    private final CreditAccount creditAccount = CreditAccount.createNew(
            "55".repeat(7),
            "5".repeat(32),
            Customer.createNew("customer@example.com", "{bcrypt}password-hash", "Ada Lovelace"),
            CurrencyCode.TRY,
            new BigDecimal("1000.00"),
            new BigDecimal("0.2499"),
            PERIOD_START
    );

    @Test
    void createNewPreservesValuesAndNormalizesAmounts() {
        BankTransaction interestTransaction = interestTransaction("12.30");

        CreditStatement statement = CreditStatement.createNew(
                STATEMENT_REFERENCE,
                creditAccount,
                PERIOD_START,
                STATEMENT_DATE,
                DUE_DATE,
                new BigDecimal("100"),
                new BigDecimal("612.3"),
                new BigDecimal("450"),
                new BigDecimal("12.3"),
                new BigDecimal("25"),
                interestTransaction
        );

        assertThat(statement.getStatementReference()).isEqualTo(STATEMENT_REFERENCE);
        assertThat(statement.getCreditAccount()).isSameAs(creditAccount);
        assertThat(statement.getPeriodStart()).isEqualTo(PERIOD_START);
        assertThat(statement.getStatementDate()).isEqualTo(STATEMENT_DATE);
        assertThat(statement.getDueDate()).isEqualTo(DUE_DATE);
        assertThat(statement.getOpeningBalance()).isEqualTo(new BigDecimal("100.00"));
        assertThat(statement.getClosingBalance()).isEqualTo(new BigDecimal("612.30"));
        assertThat(statement.getAverageDailyBalance()).isEqualTo(new BigDecimal("450.00"));
        assertThat(statement.getInterestCharged()).isEqualTo(new BigDecimal("12.30"));
        assertThat(statement.getMinimumPayment()).isEqualTo(new BigDecimal("25.00"));
        assertThat(statement.getInterestTransaction()).isSameAs(interestTransaction);
    }

    @Test
    void createNewAllowsNoInterestWithoutTransaction() {
        CreditStatement statement = statement(new BigDecimal("0.00"), null);

        assertThat(statement.getInterestCharged()).isEqualTo(new BigDecimal("0.00"));
        assertThat(statement.getInterestTransaction()).isNull();
    }

    @Test
    void createNewAllowsACreditBalanceWithZeroMinimumPayment() {
        CreditStatement statement = CreditStatement.createNew(
                STATEMENT_REFERENCE, creditAccount, PERIOD_START, STATEMENT_DATE, DUE_DATE,
                new BigDecimal("0.00"), new BigDecimal("-30.00"), new BigDecimal("0.00"),
                new BigDecimal("0.00"), new BigDecimal("0.00"), null
        );

        assertThat(statement.getClosingBalance()).isEqualTo(new BigDecimal("-30.00"));
        assertThat(statement.getMinimumPayment()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void createNewRejectsInvalidReference() {
        assertThatThrownBy(() -> CreditStatement.createNew(
                "not-a-reference", creditAccount, PERIOD_START, STATEMENT_DATE, DUE_DATE,
                new BigDecimal("0.00"), new BigDecimal("100.00"), new BigDecimal("50.00"),
                new BigDecimal("0.00"), new BigDecimal("25.00"), null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Statement reference must be exactly 32 hexadecimal characters");
    }

    @Test
    void createNewRejectsPeriodStartOnStatementDate() {
        assertThatThrownBy(() -> CreditStatement.createNew(
                STATEMENT_REFERENCE, creditAccount, STATEMENT_DATE, STATEMENT_DATE, DUE_DATE,
                new BigDecimal("0.00"), new BigDecimal("100.00"), new BigDecimal("50.00"),
                new BigDecimal("0.00"), new BigDecimal("25.00"), null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Period start must be before the statement date");
    }

    @Test
    void createNewRejectsDueDateOnStatementDate() {
        assertThatThrownBy(() -> CreditStatement.createNew(
                STATEMENT_REFERENCE, creditAccount, PERIOD_START, STATEMENT_DATE, STATEMENT_DATE,
                new BigDecimal("0.00"), new BigDecimal("100.00"), new BigDecimal("50.00"),
                new BigDecimal("0.00"), new BigDecimal("25.00"), null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Due date must be after the statement date");
    }

    @ParameterizedTest
    @CsvSource({
            "-1.00, Interest charged cannot be negative",
            "1.001, Interest charged must not have more than 2 decimal places"
    })
    void createNewRejectsInvalidInterest(BigDecimal interest, String message) {
        assertThatThrownBy(() -> statement(interest, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void createNewRejectsMinimumPaymentAboveClosingBalance() {
        assertThatThrownBy(() -> CreditStatement.createNew(
                STATEMENT_REFERENCE, creditAccount, PERIOD_START, STATEMENT_DATE, DUE_DATE,
                new BigDecimal("0.00"), new BigDecimal("10.00"), new BigDecimal("5.00"),
                new BigDecimal("0.00"), new BigDecimal("10.01"), null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Minimum payment cannot exceed the closing balance");
    }

    @Test
    void createNewRejectsInterestWithoutTransaction() {
        assertThatThrownBy(() -> statement(new BigDecimal("5.00"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An interest transaction is required exactly when interest is charged");
    }

    @Test
    void createNewRejectsTransactionWithoutInterest() {
        assertThatThrownBy(() -> statement(new BigDecimal("0.00"), interestTransaction("5.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An interest transaction is required exactly when interest is charged");
    }

    @Test
    void createNewRejectsTransactionOfAnotherType() {
        BankTransaction purchase = BankTransaction.createNew(
                new BigDecimal("5.00"), CurrencyCode.TRY, TransactionType.CARD_PURCHASE, "c".repeat(32), null
        );

        assertThatThrownBy(() -> statement(new BigDecimal("5.00"), purchase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Interest transaction must be of type CREDIT_CARD_INTEREST");
    }

    private CreditStatement statement(BigDecimal interestCharged, BankTransaction interestTransaction) {
        return CreditStatement.createNew(
                STATEMENT_REFERENCE,
                creditAccount,
                PERIOD_START,
                STATEMENT_DATE,
                DUE_DATE,
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                interestCharged,
                new BigDecimal("25.00"),
                interestTransaction
        );
    }

    private static BankTransaction interestTransaction(String amount) {
        return BankTransaction.createNew(
                new BigDecimal(amount), CurrencyCode.TRY, TransactionType.CREDIT_CARD_INTEREST, "b".repeat(32), null
        );
    }
}
