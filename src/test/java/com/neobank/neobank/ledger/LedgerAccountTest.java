package com.neobank.neobank.ledger;

import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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

    @Test
    void creditRejectsNullAmount() {
        assertThatThrownBy(() -> ledgerAccount.credit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not be null");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void creditRejectsZeroAmount() {
        assertThatThrownBy(() -> ledgerAccount.credit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void creditRejectsNegativeAmount() {
        assertThatThrownBy(() -> ledgerAccount.credit(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void creditRejectsAmountWithMoreThanTwoMeaningfulDecimalPlaces() {
        assertThatThrownBy(() -> ledgerAccount.credit(new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not have more than 2 decimal places");

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

    @Test
    void debitRejectsNullAmount() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not be null");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitRejectsZeroAmount() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitRejectsNegativeAmount() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitRejectsAmountWithMoreThanTwoMeaningfulDecimalPlaces() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not have more than 2 decimal places");

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitThrowsInsufficientFundsExceptionWhenBalanceIsTooLow() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");
    }

    @Test
    void failedOperationLeavesBalanceUnchanged() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> ledgerAccount.debit(new BigDecimal("150.00")))
                .isInstanceOf(InsufficientFundsException.class);

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

    @Test
    void createNewRejectsNullReference() {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                null,
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger reference cannot be null");
    }

    @Test
    void createNewRejectsEmptyReference() {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                "",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger reference must be exactly 32 hexadecimal characters");
    }

    @Test
    void createNewRejectsReferenceShorterThan32Characters() {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                "0123456789abcdef0123456789abcde",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Ledger reference must be exactly 32 hexadecimal characters"
                );
    }

    @Test
    void createNewRejectsReferenceLongerThan32Characters() {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                "0123456789abcdef0123456789abcdef0",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Ledger reference must be exactly 32 hexadecimal characters"
                );
    }

    @Test
    void createNewRejectsNonHexadecimalCharacters() {
        assertThatThrownBy(() -> LedgerAccount.createNew(
                "0123456789abcdef0123456789abcdeg",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Ledger reference must be exactly 32 hexadecimal characters"
                );
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
}