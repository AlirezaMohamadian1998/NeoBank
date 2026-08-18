package com.neobank.neobank.transaction;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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

    @Test
    void nullReferenceIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                null,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reference must not be null");
    }

    @Test
    void referenceShorterThan32CharactersIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                "1111111111111111111111111111111",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reference must be exactly 32 hexadecimal characters");
    }

    @Test
    void referenceLongerThan32CharactersIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                "111111111111111111111111111111111",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reference must be exactly 32 hexadecimal characters");
    }

    @Test
    void nonHexadecimalReferenceIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                "1111111111111111111111111111111g",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reference must be exactly 32 hexadecimal characters");
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

    @Test
    void nullAmountIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                null,
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not be null");
    }

    @Test
    void zeroAmountIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");
    }

    @Test
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("-1.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");
    }

    @Test
    void amountWithMoreThanTwoMeaningfulDecimalPlacesIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.001"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not have more than 2 decimal places");
    }

    @Test
    void nullBalanceAfterIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                null,
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Balance after must not be null");
    }

    @Test
    void negativeBalanceAfterIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                new BigDecimal("-0.01"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Balance after must not be less than zero");
    }

    @Test
    void balanceAfterWithMoreThanTwoMeaningfulDecimalPlacesIsRejected() {
        assertThatThrownBy(() -> LedgerEntry.createNew(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("10.00"),
                new BigDecimal("100.001"),
                EntryDirection.CREDIT,
                ledgerAccount,
                bankTransaction
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Balance after must not have more than 2 decimal places");
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
}
