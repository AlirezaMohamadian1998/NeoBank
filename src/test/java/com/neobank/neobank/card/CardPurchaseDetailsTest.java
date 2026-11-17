package com.neobank.neobank.card;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardPurchaseDetailsTest {

    @Test
    void createNewAcceptsCompletedCardPurchase() {
        BankTransaction transaction = createTransaction(TransactionType.DEBIT_CARD_PURCHASE);

        transaction.complete();

        CardPurchaseDetails details = CardPurchaseDetails.createNew(
                transaction,
                "Test merchant",
                "6".repeat(32),
                "1234"
        );

        assertThat(details.getBankTransaction())
                .isSameAs(transaction);

        assertThat(details.getMerchantName())
                .isEqualTo("Test merchant");

        assertThat(details.getCardReference())
                .isEqualTo("6".repeat(32));

        assertThat(details.getCardLastFourDigits())
                .isEqualTo("1234");
    }

    @Test
    void createNewRejectsNullTransaction() {
        assertThatThrownBy(() -> CardPurchaseDetails.createNew(
                null,
                "Test merchant",
                "6".repeat(32),
                "1234"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bank transaction must not be null");
    }

    @Test
    void createNewRejectsWrongTransactionType() {
        BankTransaction transaction = createTransaction(TransactionType.DEPOSIT);

        transaction.complete();

        assertThatThrownBy(() -> CardPurchaseDetails.createNew(
                transaction,
                "Test merchant",
                "6".repeat(32),
                "1234"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The transaction type is not correct");
    }

    @Test
    void createNewRejectsUncompletedTransaction() {
        BankTransaction transaction = createTransaction(TransactionType.DEBIT_CARD_PURCHASE);

        assertThatThrownBy(() -> CardPurchaseDetails.createNew(
                transaction,
                "Test merchant",
                "6".repeat(32),
                "1234"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction must be completed");
    }

    @ParameterizedTest
    @MethodSource("invalidMerchantNames")
    void createNewRejectsInvalidMerchantName(String merchantName, String expectedExceptionMessage) {
        BankTransaction transaction = createTransaction(TransactionType.DEBIT_CARD_PURCHASE);
        transaction.complete();

        assertThatThrownBy(() -> CardPurchaseDetails.createNew(
                transaction,
                merchantName,
                "6".repeat(32),
                "1234"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedExceptionMessage);
    }

    @ParameterizedTest
    @MethodSource("invalidCardReference")
    void createNewRejectsInvalidCardReference(String cardReference, String expectedExceptionMessage) {
        BankTransaction transaction = createTransaction(TransactionType.DEBIT_CARD_PURCHASE);
        transaction.complete();

        assertThatThrownBy(() -> CardPurchaseDetails.createNew(
                transaction,
                "Test merchant",
                cardReference,
                "1234"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedExceptionMessage);
    }

    @ParameterizedTest
    @MethodSource("invalidCardLastFourDigits")
    void createNewRejectsInvalidCardLastFourDigits(String cardLastFourDigits, String expectedExceptionMessage) {
        BankTransaction transaction = createTransaction(TransactionType.DEBIT_CARD_PURCHASE);
        transaction.complete();

        assertThatThrownBy(() -> CardPurchaseDetails.createNew(
                transaction,
                "Test merchant",
                "6".repeat(32),
                cardLastFourDigits
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedExceptionMessage);
    }

    static Stream<Arguments> invalidMerchantNames() {
        return Stream.of(
                Arguments.of(null, "Merchant name must not be null"),
                Arguments.of("", "Merchant name must not be blank"),
                Arguments.of("    ", "Merchant name must not be blank"),
                Arguments.of("123".repeat(50), "Merchant name must not exceed 128 characters")
        );
    }

    static Stream<Arguments> invalidCardReference() {
        return Stream.of(
                Arguments.of("", "Card reference must be exactly 32 hexadecimal characters"),
                Arguments.of("1".repeat(44), "Card reference must be exactly 32 hexadecimal characters"),
                Arguments.of("1".repeat(20), "Card reference must be exactly 32 hexadecimal characters"),
                Arguments.of("qq1z2x3m4y5u6s7g8h9j0p23afccwwdd", "Card reference must be exactly 32 hexadecimal characters"),
                Arguments.of(null, "Card reference must not be null")
        );
    }

    static Stream<Arguments> invalidCardLastFourDigits() {
        return Stream.of(
                Arguments.of("", "Card last four digits must be exactly 4 digits"),
                Arguments.of("123", "Card last four digits must be exactly 4 digits"),
                Arguments.of("12345", "Card last four digits must be exactly 4 digits"),
                Arguments.of("12a4", "Card last four digits must be exactly 4 digits"),
                Arguments.of(null, "Card last four digits must not be null")
        );
    }

    private BankTransaction createTransaction(TransactionType type) {
        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                type,
                "1".repeat(32),
                null
        );

        LedgerAccount ledgerAccount1 = LedgerAccount.createNew(
                "2".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        LedgerAccount ledgerAccount2 = LedgerAccount.createNew(
                "3".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        transaction.addEntry(
                "4".repeat(32),
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                EntryDirection.DEBIT,
                ledgerAccount1
        );

        transaction.addEntry(
                "5".repeat(32),
                new BigDecimal("100.00"),
                new BigDecimal("200.00"),
                EntryDirection.CREDIT,
                ledgerAccount2
        );

        return transaction;
    }
}
