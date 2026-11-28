package com.neobank.neobank.transaction;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankTransactionTest {

    private static final String VALID_TRANSACTION_REFERENCE =
            "0123456789abcdef0123456789abcdef";

    private static final String VALID_LEDGER_REFERENCE =
            "abcdef0123456789abcdef0123456789";

    private static final String VALID_ENTRY_REFERENCE =
            "11111111111111111111111111111111";

    private LedgerAccount ledgerAccount;

    @BeforeEach
    void setUp() {

        ledgerAccount = LedgerAccount.createNew(
                VALID_LEDGER_REFERENCE,
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
    }

    @Test
    void validCreationPreservesAllFields() {
        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("125.50"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                "Monthly payment"
        );

        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(new BigDecimal("125.50"));

        assertThat(transaction.getRequestedCurrency())
                .isEqualTo(CurrencyCode.TRY);

        assertThat(transaction.getTransactionType())
                .isEqualTo(TransactionType.DEPOSIT);

        assertThat(transaction.getReference())
                .isEqualTo(VALID_TRANSACTION_REFERENCE);

        assertThat(transaction.getNote())
                .isEqualTo("Monthly payment");
    }

    @Test
    void requestedAmountIsNormalizedToTwoDecimalPlaces() {
        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.000"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                null
        );

        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(transaction.getRequestedAmount().scale())
                .isEqualTo(2);
    }

    @Test
    void newTransactionStartsPending() {
        BankTransaction transaction = createTransaction();

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void newTransactionHasNoEntries() {
        BankTransaction transaction = createTransaction();

        assertThat(transaction.getEntries())
                .isNotNull()
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            ", Amount must not be null",
            "0, Amount must be greater than zero",
            "-1.00, Amount must be greater than zero",
            "10.001, Amount must not have more than 2 decimal places"
    })
    void invalidRequestedAmountIsRejected(BigDecimal amount, String message) {
        assertThatThrownBy(() -> BankTransaction.createNew(
                amount,
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void nullRequestedCurrencyIsRejected() {
        assertThatThrownBy(() -> BankTransaction.createNew(
                new BigDecimal("100.00"),
                null,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Currency cannot be null");
    }

    @Test
    void nullTransactionTypeIsRejected() {
        assertThatThrownBy(() -> BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                null,
                VALID_TRANSACTION_REFERENCE,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction type cannot be null");
    }

    @ParameterizedTest
    @CsvSource({
            ", Reference cannot be null",
            "'', Reference must be exactly 32 hexadecimal characters",
            "0123456789abcdef0123456789abcde, Reference must be exactly 32 hexadecimal characters",
            "0123456789abcdef0123456789abcdef0, Reference must be exactly 32 hexadecimal characters",
            "0123456789abcdef0123456789abcdeg, Reference must be exactly 32 hexadecimal characters"
    })
    void invalidReferenceIsRejected(String reference, String message) {
        assertThatThrownBy(() -> BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                reference,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void noteIsTrimmed() {
        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                "   Monthly payment   "
        );

        assertThat(transaction.getNote())
                .isEqualTo("Monthly payment");
    }

    @Test
    void blankNoteBecomesNull() {
        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                "     "
        );

        assertThat(transaction.getNote())
                .isNull();
    }

    @Test
    void nullNoteRemainsNull() {
        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                null
        );

        assertThat(transaction.getNote())
                .isNull();
    }

    @Test
    void noteWith255CharactersIsAccepted() {
        String note = "a".repeat(255);

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                note
        );

        assertThat(transaction.getNote())
                .isEqualTo(note);
    }

    @Test
    void noteLongerThan255CharactersIsRejected() {
        String note = "a".repeat(256);

        assertThatThrownBy(() -> BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                VALID_TRANSACTION_REFERENCE,
                note
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Transaction note cannot exceed 255 characters"
                );
    }

    @Test
    void addEntryAddsEntryConnectedToSameTransaction() {
        BankTransaction transaction = createTransaction();

        LedgerEntry entry = transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        assertThat(transaction.getEntries())
                .hasSize(1)
                .containsExactly(entry);

        assertThat(entry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(entry.getReference())
                .isEqualTo(VALID_ENTRY_REFERENCE);
    }

    @Test
    void transactionWithEntriesCanComplete() {
        BankTransaction transaction = createTransaction();

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE.replace("1", "2"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.complete();

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void transactionWithoutEntriesCannotComplete() {
        BankTransaction transaction = createTransaction();

        assertThatThrownBy(transaction::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Transaction cannot be completed without entries"
                );

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void transactionWithIncorrectDebitAmountCannotComplete() {
        BankTransaction transaction = createTransaction();

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE.replace("1", "2"),
                new BigDecimal("90.00"),
                new BigDecimal("90.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        assertThatThrownBy(transaction::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Debit entry amount does not match the expected amount");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void crossCurrencyTransactionWithoutFxInfoCannotComplete() {
        BankTransaction transaction = createTransaction();

        LedgerAccount usdLedgerAccount = LedgerAccount.createNew(
                "22222222222222222222222222222222",
                LedgerAccountType.ASSET,
                CurrencyCode.USD
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        transaction.addEntry(
                "33333333333333333333333333333333",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                usdLedgerAccount
        );

        assertThatThrownBy(transaction::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fx info must not be null when the currencies differ");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void crossCurrencyTransactionWithFxInfoCanComplete() {
        BankTransaction transaction = createTransaction();

        LedgerAccount usdLedgerAccount = LedgerAccount.createNew(
                "22222222222222222222222222222222",
                LedgerAccountType.LIABILITY,
                CurrencyCode.USD
        );

        FxInfo fxInfo = createFxInfo(
                CurrencyCode.TRY,
                BigDecimal.ONE,
                CurrencyCode.USD,
                new BigDecimal("0.025")
        );

        transaction.addFxInfo(fxInfo);

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE.replace("1", "2"),
                new BigDecimal("2.50"),
                new BigDecimal("2.50"),
                EntryDirection.CREDIT,
                usdLedgerAccount
        );

        transaction.complete();

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);

        assertThat(transaction.getFxInfo())
                .isSameAs(fxInfo);
    }

    @Test
    void sameCurrencyTransactionWithFxInfoCannotComplete() {
        BankTransaction transaction = createTransaction();

        transaction.addFxInfo(createFxInfo(
                CurrencyCode.TRY,
                BigDecimal.ONE,
                CurrencyCode.TRY,
                BigDecimal.ONE
        ));

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE.replace("1", "2"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        assertThatThrownBy(transaction::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Fx info must be null when the currencies are same");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void completedTransactionCannotAcceptAnotherEntry() {
        BankTransaction transaction = createTransaction();

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE.replace("1", "2"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.complete();

        assertThatThrownBy(() -> transaction.addEntry(
                "22222222222222222222222222222222",
                new BigDecimal("50.00"),
                new BigDecimal("150.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Transaction is not pending");

        assertThat(transaction.getEntries())
                .hasSize(2);

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void completedTransactionCannotBeCompletedAgain() {
        BankTransaction transaction = createTransaction();

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE.replace("1", "2"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.complete();

        assertThatThrownBy(transaction::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Transaction is not pending");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void completedCardPurchaseCanBeMarkedRefunded() {
        BankTransaction transaction = createCompletedTransaction(TransactionType.CARD_PURCHASE);

        transaction.markRefunded();

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.REFUNDED);
    }

    @Test
    void refundedCardPurchaseCannotBeMarkedRefundedAgain() {
        BankTransaction transaction = createCompletedTransaction(TransactionType.CARD_PURCHASE);
        transaction.markRefunded();

        assertThatThrownBy(transaction::markRefunded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only completed transactions can be refunded");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.REFUNDED);
    }

    @Test
    void pendingCardPurchaseCannotBeMarkedRefunded() {
        BankTransaction transaction = createTransaction(TransactionType.CARD_PURCHASE);

        assertThatThrownBy(transaction::markRefunded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only completed transactions can be refunded");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void nonCardPurchaseCannotBeMarkedRefunded() {
        BankTransaction transaction = createCompletedTransaction(TransactionType.TRANSFER);

        assertThatThrownBy(transaction::markRefunded)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only card purchases can be refunded");

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    private BankTransaction createTransaction() {
        return createTransaction(TransactionType.DEPOSIT);
    }

    private BankTransaction createTransaction(TransactionType transactionType) {
        return BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                transactionType,
                VALID_TRANSACTION_REFERENCE,
                null
        );
    }

    private BankTransaction createCompletedTransaction(TransactionType transactionType) {
        BankTransaction transaction = createTransaction(transactionType);

        transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        transaction.addEntry(
                VALID_ENTRY_REFERENCE.replace("1", "2"),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.complete();

        return transaction;
    }

    private FxInfo createFxInfo(
            CurrencyCode sourceCurrency,
            BigDecimal sourceRate,
            CurrencyCode destinationCurrency,
            BigDecimal destinationRate
    ) {
        return FxInfo.createNew(
                "4ea56d0d-aa07-4d26-be23-ce971c0976a0",
                Set.of(
                        FxRate.createNew(
                                CurrencyContext.REQUEST,
                                CurrencyCode.TRY,
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.SOURCE,
                                sourceCurrency,
                                sourceRate
                        ),
                        FxRate.createNew(
                                CurrencyContext.DESTINATION,
                                destinationCurrency,
                                destinationRate
                        )
                )
        );
    }

    @Test
    void entriesCannotBeModifiedExternally() {
        BankTransaction transaction = createTransaction();

        LedgerEntry entry = transaction.addEntry(
                VALID_ENTRY_REFERENCE,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        assertThatThrownBy(() -> transaction.getEntries().clear())
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(transaction.getEntries())
                .containsExactly(entry);
    }
}
