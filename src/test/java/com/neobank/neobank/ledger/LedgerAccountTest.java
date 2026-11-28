package com.neobank.neobank.ledger;

import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerAccountTest {

    private LedgerAccount ledgerAccount;

    @BeforeEach
    void setUp() {
        ledgerAccount = LedgerAccount.createNew(
                "0123456789abcdef0123456789abcdef",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
    }

    @Test
    void liabilityCreditIncreasesAndReturnsBalance() {
        BigDecimal result = ledgerAccount.credit(new BigDecimal("125.50"));

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("125.50"));

        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("125.50"));
    }

    @Test
    void creditAccumulatesBalance() {
        ledgerAccount.credit(new BigDecimal("10.25"));

        BigDecimal result =
                ledgerAccount.credit(new BigDecimal("2.75"));

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("13.00"));

        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("13.00"));
    }

    @Test
    void creditAcceptsInsignificantTrailingZeros() {
        ledgerAccount.credit(new BigDecimal("10.000"));

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("10.00"));

        assertThat(ledgerAccount.getBalance().scale())
                .isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
            ", Amount must not be null",
            "0, Amount must be greater than zero",
            "-10.00, Amount must be greater than zero",
            "10.001, Amount must not have more than 2 decimal places"
    })
    void creditRejectsInvalidAmount(BigDecimal amount, String message) {
        assertThatThrownBy(() -> ledgerAccount.credit(amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void liabilityDebitDecreasesAndReturnsBalance() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        BigDecimal result =
                ledgerAccount.debit(new BigDecimal("50.00"));

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("50.00"));

        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void debitCanReduceBalanceToZero() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        BigDecimal result =
                ledgerAccount.debit(new BigDecimal("100.00"));

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @ParameterizedTest
    @CsvSource({
            ", Amount must not be null",
            "0, Amount must be greater than zero",
            "-10.00, Amount must be greater than zero",
            "10.001, Amount must not have more than 2 decimal places"
    })
    void debitRejectsInvalidAmount(BigDecimal amount, String message) {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(amount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitThrowsInsufficientFundsExceptionWhenBalanceIsTooLow() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void assetDebitIncreasesBalance() {
        LedgerAccount assetAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assetAccount.debit(new BigDecimal("100.00"));

        assertThat(assetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void assetCreditDecreasesBalance() {
        LedgerAccount assetAccount = LedgerAccount.createNew(
                "22222222222222222222222222222222",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assetAccount.debit(new BigDecimal("100.00"));
        assetAccount.credit(new BigDecimal("40.00"));

        assertThat(assetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void closedAccountRejectsDebit() {
        ledgerAccount.close();

        assertThatThrownBy(() -> ledgerAccount.debit(new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ledger account is not active");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void closedAccountRejectsCredit() {
        ledgerAccount.close();

        assertThatThrownBy(() -> ledgerAccount.credit(new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ledger account is not active");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void zeroBalanceAccountCanClose() {
        ledgerAccount.close();

        assertThat(ledgerAccount.getStatus())
                .isEqualTo(LedgerAccountStatus.CLOSED);
    }

    @Test
    void nonZeroBalanceAccountCannotClose() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(ledgerAccount::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ledger account cannot be closed with a non-zero balance");

        assertThat(ledgerAccount.getStatus())
                .isEqualTo(LedgerAccountStatus.ACTIVE);

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void newAccountStartsActive() {
        assertThat(ledgerAccount.getStatus())
                .isEqualTo(LedgerAccountStatus.ACTIVE);
    }

    @Test
    void newAccountStartsWithZeroBalance() {
        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        assertThat(ledgerAccount.getBalance().scale())
                .isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
            ", Ledger reference cannot be null",
            "'', Ledger reference must be exactly 32 hexadecimal characters",
            "0123456789abcdef0123456789abcde, Ledger reference must be exactly 32 hexadecimal characters",
            "0123456789abcdef0123456789abcdef0, Ledger reference must be exactly 32 hexadecimal characters",
            "0123456789abcdef0123456789abcdeg, Ledger reference must be exactly 32 hexadecimal characters"
    })
    void createNewRejectsInvalidReference(String reference, String message) {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                reference,
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void createNewRejectsNullLedgerAccountType() {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                "0123456789abcdef0123456789abcdef",
                null,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger account type cannot be null");
    }

    @Test
    void createNewRejectsNullCurrency() {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                "0123456789abcdef0123456789abcdef",
                LedgerAccountType.LIABILITY,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Currency cannot be null");
    }

    @Test
    void createNewPreservesReferenceTypeAndCurrency() {
        String reference = "0123456789abcdef0123456789abcdef";

        LedgerAccount result = LedgerAccount.createNew(
                reference,
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        assertThat(result.getLedgerReference())
                .isEqualTo(reference);

        assertThat(result.getType())
                .isEqualTo(LedgerAccountType.LIABILITY);

        assertThat(result.getCurrency())
                .isEqualTo(CurrencyCode.TRY);
    }

    @Test
    void assetCreditBelowZeroThrowsAndLeavesBalanceUnchanged() {
        LedgerAccount assetAccount = LedgerAccount.createNew(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assertThat(assetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        assertThatThrownBy(
                () -> assetAccount.credit(new BigDecimal("1.00"))
        )
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(assetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void newAccountDoesNotAllowNegativeBalance() {
        assertThat(ledgerAccount.isNegativeBalanceAllowed()).isFalse();
    }

    @Test
    void createNewWithNegativeBalanceAllowedSetsFlagAndKeepsDefaults() {
        LedgerAccount result = LedgerAccount.createNewWithNegativeBalanceAllowed(
                "0123456789abcdef0123456789abcdef",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        assertThat(result.isNegativeBalanceAllowed()).isTrue();
        assertThat(result.getType()).isEqualTo(LedgerAccountType.ASSET);
        assertThat(result.getCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(result.getStatus()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void createNewWithNegativeBalanceAllowedValidatesLikeCreateNew() {
        assertThatThrownBy(() -> LedgerAccount.createNewWithNegativeBalanceAllowed(
                null,
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger reference cannot be null");
    }

    @Test
    void assetCreditBelowZeroIsAllowedWhenNegativeBalanceIsAllowed() {
        LedgerAccount assetAccount = LedgerAccount.createNewWithNegativeBalanceAllowed(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assetAccount.debit(new BigDecimal("30.00"));

        BigDecimal balance = assetAccount.credit(new BigDecimal("100.00"));

        assertThat(balance).isEqualByComparingTo(new BigDecimal("-70.00"));
        assertThat(assetAccount.getBalance()).isEqualByComparingTo(new BigDecimal("-70.00"));
    }

    @Test
    void negativeBalanceGoesBackUpWithDebits() {
        LedgerAccount assetAccount = LedgerAccount.createNewWithNegativeBalanceAllowed(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assetAccount.credit(new BigDecimal("50.00"));
        assetAccount.debit(new BigDecimal("80.00"));

        assertThat(assetAccount.getBalance()).isEqualByComparingTo(new BigDecimal("30.00"));
    }

    @Test
    void accountWithNegativeBalanceCannotClose() {
        LedgerAccount assetAccount = LedgerAccount.createNewWithNegativeBalanceAllowed(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assetAccount.credit(new BigDecimal("10.00"));

        assertThatThrownBy(assetAccount::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Ledger account cannot be closed with a non-zero balance");
    }

    @Test
    void incomeCreditIncreasesBalance() {
        LedgerAccount incomeAccount = LedgerAccount.createNew(
                "33333333333333333333333333333333",
                LedgerAccountType.INCOME,
                CurrencyCode.TRY
        );

        BigDecimal result = incomeAccount.credit(new BigDecimal("12.34"));

        assertThat(incomeAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("12.34"));

        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("12.34"));
    }

    @Test
    void incomeDebitDecreasesBalance() {
        LedgerAccount incomeAccount = LedgerAccount.createNew(
                "44444444444444444444444444444444",
                LedgerAccountType.INCOME,
                CurrencyCode.TRY
        );

        incomeAccount.credit(new BigDecimal("100.00"));
        BigDecimal result = incomeAccount.debit(new BigDecimal("40.00"));

        assertThat(incomeAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("60.00"));

        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void incomeDebitBelowZeroThrowsAndLeavesBalanceUnchanged() {
        LedgerAccount incomeAccount = LedgerAccount.createNew(
                "55555555555555555555555555555555",
                LedgerAccountType.INCOME,
                CurrencyCode.TRY
        );

        incomeAccount.credit(new BigDecimal("10.00"));

        assertThatThrownBy(() -> incomeAccount.debit(new BigDecimal("10.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(incomeAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("10.00"));
    }
}
