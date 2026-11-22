package com.neobank.neobank.creditaccount;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditAccountTest {

    private static final String ACCOUNT_NUMBER = "55".repeat(7);

    private static final String LEDGER_REFERENCE = "5".repeat(32);

    private final Customer customer = Customer.createNew("customer@example.com", "{bcrypt}password-hash", "Ada Lovelace");

    @Test
    void createNewPreservesValuesAndNormalizesLimit() {
        CreditAccount creditAccount = CreditAccount.createNew(
                ACCOUNT_NUMBER,
                LEDGER_REFERENCE,
                customer,
                CurrencyCode.TRY,
                new BigDecimal("1000"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        );

        assertThat(creditAccount.getAccountNumber()).isEqualTo(ACCOUNT_NUMBER);
        assertThat(creditAccount.getCustomer()).isSameAs(customer);
        assertThat(creditAccount.getCreditLimit()).isEqualTo(new BigDecimal("1000.00"));
        assertThat(creditAccount.getCurrency()).isEqualTo(CurrencyCode.TRY);
        assertThat(creditAccount.getAvailableCredit()).isEqualByComparingTo("1000.00");
    }

    @Test
    void createNewCreatesAnActiveAssetLedgerThatAllowsNegativeBalance() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));

        LedgerAccount ledgerAccount = creditAccount.getLedgerAccount();

        assertThat(ledgerAccount.getLedgerReference()).isEqualTo(LEDGER_REFERENCE);
        assertThat(ledgerAccount.getType()).isEqualTo(LedgerAccountType.ASSET);
        assertThat(ledgerAccount.getCurrency()).isEqualTo(CurrencyCode.TRY);
        assertThat(ledgerAccount.isNegativeBalanceAllowed()).isTrue();
        assertThat(ledgerAccount.getStatus()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(ledgerAccount.getBalance()).isEqualByComparingTo("0.00");
    }

    @ParameterizedTest
    @MethodSource("invalidAccountNumbers")
    void createNewRejectsInvalidAccountNumber(String accountNumber) {
        assertThatThrownBy(() -> CreditAccount.createNew(
                accountNumber,
                LEDGER_REFERENCE,
                customer,
                CurrencyCode.TRY,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account number must be exactly 14 numeric characters");
    }

    @Test
    void createNewRejectsNullCustomer() {
        assertThatThrownBy(() -> CreditAccount.createNew(
                ACCOUNT_NUMBER,
                LEDGER_REFERENCE,
                null,
                CurrencyCode.TRY,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer cannot be null");
    }

    @Test
    void createNewRejectsInvalidLedgerReference() {
        assertThatThrownBy(() -> CreditAccount.createNew(
                ACCOUNT_NUMBER,
                "not-a-reference",
                customer,
                CurrencyCode.TRY,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger reference must be exactly 32 hexadecimal characters");
    }

    @Test
    void createNewRejectsNullCurrency() {
        assertThatThrownBy(() -> CreditAccount.createNew(
                ACCOUNT_NUMBER,
                LEDGER_REFERENCE,
                customer,
                null,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Currency cannot be null");
    }

    @ParameterizedTest
    @MethodSource("invalidCreditLimits")
    void createNewRejectsInvalidCreditLimit(BigDecimal creditLimit, String message) {
        assertThatThrownBy(() -> creditAccount(creditLimit))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void ensureWithinCreditLimitAcceptsAmountUpToTheLimit() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));
        creditAccount.getLedgerAccount().debit(new BigDecimal("600.00"));

        creditAccount.ensureWithinCreditLimit(new BigDecimal("400.00"));

        assertThat(creditAccount.getAvailableCredit()).isEqualByComparingTo("400.00");
    }

    @Test
    void ensureWithinCreditLimitRejectsAmountOneCentOverTheLimit() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));
        creditAccount.getLedgerAccount().debit(new BigDecimal("600.00"));

        assertThatThrownBy(() -> creditAccount.ensureWithinCreditLimit(new BigDecimal("400.01")))
                .isInstanceOf(CreditLimitExceededException.class)
                .hasMessage("Amount exceeds the available credit of 400.00");
    }

    @Test
    void ensureWithinCreditLimitRejectsNullAmount() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));

        assertThatThrownBy(() -> creditAccount.ensureWithinCreditLimit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not be null");
    }

    @Test
    void creditBalanceMakesAvailableCreditExceedTheLimit() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));
        creditAccount.getLedgerAccount().credit(new BigDecimal("150.00"));

        assertThat(creditAccount.getLedgerAccount().getBalance()).isEqualByComparingTo("-150.00");
        assertThat(creditAccount.getAvailableCredit()).isEqualByComparingTo("1150.00");

        creditAccount.ensureWithinCreditLimit(new BigDecimal("1150.00"));
    }

    @Test
    void ensureWithinOutstandingBalanceAcceptsTheFullBalance() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));
        creditAccount.getLedgerAccount().debit(new BigDecimal("600.00"));

        creditAccount.ensureWithinOutstandingBalance(new BigDecimal("600.00"));
    }

    @Test
    void ensureWithinOutstandingBalanceRejectsAmountOneCentOverTheBalance() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));
        creditAccount.getLedgerAccount().debit(new BigDecimal("600.00"));

        assertThatThrownBy(() -> creditAccount.ensureWithinOutstandingBalance(new BigDecimal("600.01")))
                .isInstanceOf(RepaymentExceedsBalanceException.class)
                .hasMessage("Repayment exceeds the outstanding balance of 600.00");
    }

    @Test
    void ensureWithinOutstandingBalanceRejectsAnyAmountWhenThereIsACreditBalance() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));
        creditAccount.getLedgerAccount().credit(new BigDecimal("150.00"));

        assertThatThrownBy(() -> creditAccount.ensureWithinOutstandingBalance(new BigDecimal("0.01")))
                .isInstanceOf(RepaymentExceedsBalanceException.class)
                .hasMessage("Repayment exceeds the outstanding balance of 0.00");
    }

    @Test
    void ensureWithinOutstandingBalanceRejectsNullAmount() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("1000.00"));

        assertThatThrownBy(() -> creditAccount.ensureWithinOutstandingBalance(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not be null");
    }

    private static Stream<String> invalidAccountNumbers() {
        return Stream.of(null, "", "1234567890123", "123456789012345", "1234567890123a");
    }

    @Test
    void createNewNormalizesAnnualInterestRate() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("0.25"), LocalDate.of(2026, 1, 15));

        assertThat(creditAccount.getAnnualInterestRate()).isEqualTo(new BigDecimal("0.2500"));
    }

    @Test
    void createNewAcceptsZeroAnnualInterestRate() {
        CreditAccount creditAccount = creditAccount(BigDecimal.ZERO, LocalDate.of(2026, 1, 15));

        assertThat(creditAccount.getAnnualInterestRate()).isEqualTo(new BigDecimal("0.0000"));
    }

    @ParameterizedTest
    @MethodSource("invalidAnnualInterestRates")
    void createNewRejectsInvalidAnnualInterestRate(BigDecimal annualInterestRate, String message) {
        assertThatThrownBy(() -> creditAccount(annualInterestRate, LocalDate.of(2026, 1, 15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void createNewRejectsNullOpeningDate() {
        assertThatThrownBy(() -> creditAccount(new BigDecimal("0.2499"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Opening date cannot be null");
    }

    @ParameterizedTest
    @MethodSource("firstStatementDates")
    void createNewSetsFirstStatementDateOneMonthAfterOpeningCappedAtDay28(LocalDate openedOn, LocalDate expectedStatementDate) {
        CreditAccount creditAccount = creditAccount(new BigDecimal("0.2499"), openedOn);

        assertThat(creditAccount.getNextStatementDate()).isEqualTo(expectedStatementDate);
    }

    @Test
    void advanceStatementDateMovesOneMonthAndKeepsTheDay() {
        CreditAccount creditAccount = creditAccount(new BigDecimal("0.2499"), LocalDate.of(2026, 1, 31));

        creditAccount.advanceStatementDate();
        creditAccount.advanceStatementDate();

        assertThat(creditAccount.getNextStatementDate()).isEqualTo(LocalDate.of(2026, 4, 28));
    }

    private static Stream<Arguments> invalidAnnualInterestRates() {
        return Stream.of(
                Arguments.of(null, "Annual interest rate cannot be null"),
                Arguments.of(new BigDecimal("-0.0001"), "Annual interest rate must be at least 0 and less than 1"),
                Arguments.of(BigDecimal.ONE, "Annual interest rate must be at least 0 and less than 1"),
                Arguments.of(new BigDecimal("0.12345"), "Annual interest rate must not have more than 4 decimal places")
        );
    }

    private static Stream<Arguments> firstStatementDates() {
        return Stream.of(
                Arguments.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1)),
                Arguments.of(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15)),
                Arguments.of(LocalDate.of(2026, 1, 28), LocalDate.of(2026, 2, 28)),
                Arguments.of(LocalDate.of(2026, 1, 29), LocalDate.of(2026, 2, 28)),
                Arguments.of(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28)),
                Arguments.of(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 20))
        );
    }

    private static Stream<Arguments> invalidCreditLimits() {
        return Stream.of(
                Arguments.of(null, "Credit limit cannot be null"),
                Arguments.of(BigDecimal.ZERO, "Credit limit must be greater than zero"),
                Arguments.of(new BigDecimal("-1.00"), "Credit limit must be greater than zero"),
                Arguments.of(new BigDecimal("10.001"), "Credit limit must not have more than 2 decimal places")
        );
    }

    private CreditAccount creditAccount(BigDecimal creditLimit) {
        return CreditAccount.createNew(ACCOUNT_NUMBER, LEDGER_REFERENCE, customer, CurrencyCode.TRY, creditLimit, new BigDecimal("0.2499"), LocalDate.of(2026, 1, 15));
    }

    private CreditAccount creditAccount(BigDecimal annualInterestRate, LocalDate openedOn) {
        return CreditAccount.createNew(
                ACCOUNT_NUMBER,
                LEDGER_REFERENCE,
                customer,
                CurrencyCode.TRY,
                new BigDecimal("1000.00"),
                annualInterestRate,
                openedOn
        );
    }
}
