package com.neobank.neobank.card;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardRefundDetailsTest {

    @Test
    void createNewAcceptsCompletedRefundForRefundedPurchase() {
        CardPurchaseDetails purchase = createPurchaseDetails();
        purchase.getBankTransaction().markRefunded();

        BankTransaction refund = createCompletedTransaction(TransactionType.CARD_REFUND, "a");

        CardRefundDetails details = CardRefundDetails.createNew(refund, purchase);

        assertThat(details.getBankTransaction())
                .isSameAs(refund);

        assertThat(details.getCardPurchaseDetails())
                .isSameAs(purchase);
    }

    @Test
    void createNewRejectsNullTransaction() {
        CardPurchaseDetails purchase = createPurchaseDetails();
        purchase.getBankTransaction().markRefunded();

        assertThatThrownBy(() -> CardRefundDetails.createNew(null, purchase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Bank transaction must not be null");
    }

    @Test
    void createNewRejectsWrongTransactionType() {
        CardPurchaseDetails purchase = createPurchaseDetails();
        purchase.getBankTransaction().markRefunded();

        BankTransaction transaction = createCompletedTransaction(TransactionType.DEPOSIT, "a");

        assertThatThrownBy(() -> CardRefundDetails.createNew(transaction, purchase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The transaction type is not correct");
    }

    @Test
    void createNewRejectsUncompletedTransaction() {
        CardPurchaseDetails purchase = createPurchaseDetails();
        purchase.getBankTransaction().markRefunded();

        BankTransaction refund = createTransaction(TransactionType.CARD_REFUND, "a");

        assertThatThrownBy(() -> CardRefundDetails.createNew(refund, purchase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Transaction must be completed");
    }

    @Test
    void createNewRejectsNullPurchaseDetails() {
        BankTransaction refund = createCompletedTransaction(TransactionType.CARD_REFUND, "a");

        assertThatThrownBy(() -> CardRefundDetails.createNew(refund, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Card purchase details must not be null");
    }

    @Test
    void createNewRejectsPurchaseNotMarkedRefunded() {
        CardPurchaseDetails purchase = createPurchaseDetails();
        BankTransaction refund = createCompletedTransaction(TransactionType.CARD_REFUND, "a");

        assertThatThrownBy(() -> CardRefundDetails.createNew(refund, purchase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Original purchase must be marked as refunded");
    }

    private CardPurchaseDetails createPurchaseDetails() {
        return CardPurchaseDetails.createNew(
                createCompletedTransaction(TransactionType.CARD_PURCHASE, "1"),
                "Test merchant",
                "6".repeat(32),
                "1234"
        );
    }

    private BankTransaction createTransaction(TransactionType type, String referenceDigit) {
        return BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                type,
                referenceDigit.repeat(32),
                null
        );
    }

    private BankTransaction createCompletedTransaction(TransactionType type, String referenceDigit) {
        BankTransaction transaction = createTransaction(type, referenceDigit);

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

        transaction.complete();

        return transaction;
    }
}
