package com.neobank.neobank.transaction;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LedgerEntryTest {

    private static final String VALID_ENTRY_REFERENCE =
            "11111111111111111111111111111111";

    private LedgerAccount ledgerAccount;
    private BankTransaction bankTransaction;

    @BeforeEach
    void setUp() {
        ledgerAccount = LedgerAccount.createNew(
                "0123456789abcdef0123456789abcdef",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        bankTransaction = mock(BankTransaction.class);
    }

    @Test
    void validEntryPreservesProvidedValues() {
        LedgerEntry entry = LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("250.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        );

        assertThat(entry.getAmount())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(entry.getReference())
                .isEqualTo(VALID_ENTRY_REFERENCE);

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("250.00"));

        assertThat(entry.getDirection())
                .isEqualTo(EntryDirection.CREDIT);

        assertThat(entry.getLedgerAccount())
                .isSameAs(ledgerAccount);

        assertThat(entry.getBankTransaction())
                .isSameAs(bankTransaction);
    }

    @ParameterizedTest
    @CsvSource({
            ", Reference must not be null",
            "1111111111111111111111111111111, Reference must be exactly 32 hexadecimal characters",
            "111111111111111111111111111111111, Reference must be exactly 32 hexadecimal characters",
            "1111111111111111111111111111111g, Reference must be exactly 32 hexadecimal characters"
    })
    void invalidReferenceIsRejected(String reference, String message) {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                reference,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void currencyIsCopiedFromLedgerAccount() {
        LedgerEntry entry = LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        );

        assertThat(entry.getCurrency())
                .isEqualTo(CurrencyCode.TRY);

        assertThat(entry.getCurrency())
                .isEqualTo(ledgerAccount.getCurrency());
    }

    @Test
    void amountAndBalanceAfterAreNormalizedToTwoDecimalPlaces() {
        LedgerEntry entry = LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.000"),
                new BigDecimal("25.500"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        );

        assertThat(entry.getAmount())
                .isEqualByComparingTo(new BigDecimal("10.00"));

        assertThat(entry.getAmount().scale())
                .isEqualTo(2);

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("25.50"));

        assertThat(entry.getBalanceAfter().scale())
                .isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
            ", Amount must not be null",
            "0, Amount must be greater than zero",
            "-1.00, Amount must be greater than zero",
            "10.001, Amount must not have more than 2 decimal places"
    })
    void invalidAmountIsRejected(BigDecimal amount, String message) {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                amount,
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @ParameterizedTest
    @CsvSource({
            ", Balance after must not be null",
            "-0.01, Balance after must not be less than zero",
            "100.001, Balance after must not have more than 2 decimal places"
    })
    void invalidBalanceAfterIsRejected(BigDecimal balanceAfter, String message) {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                balanceAfter,
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void zeroBalanceAfterIsAccepted() {
        LedgerEntry entry = LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                BigDecimal.ZERO,
                EntryDirection.DEBIT,
                ledgerAccount,
                bankTransaction
        );

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        assertThat(entry.getBalanceAfter().scale())
                .isEqualTo(2);
    }

    @Test
    void nullDirectionIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                null,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Entry direction cannot be null");
    }

    @Test
    void nullLedgerAccountIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                null,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger account cannot be null");
    }

    @Test
    void nullBankTransactionIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bank transaction cannot be null");
    }

    @Test
    void creatingEntryDoesNotChangeLedgerAccountBalance() {
        ledgerAccount.credit(new BigDecimal("100.00"));

        BigDecimal balanceBefore = ledgerAccount.getBalance();

        LedgerEntry entry = LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("40.00"),
                new BigDecimal("60.00"),
                EntryDirection.DEBIT,
                ledgerAccount,
                bankTransaction
        );

        assertThat(balanceBefore)
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(ledgerAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void negativeBalanceAfterIsAllowedWhenLedgerAccountAllowsNegativeBalance() {
        LedgerAccount creditLedgerAccount = LedgerAccount.createNewWithNegativeBalanceAllowed(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        LedgerEntry entry = LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                new BigDecimal("-10.00"),
                EntryDirection.CREDIT,
                creditLedgerAccount,
                bankTransaction
        );

        assertThat(entry.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("-10.00"));
    }
}
