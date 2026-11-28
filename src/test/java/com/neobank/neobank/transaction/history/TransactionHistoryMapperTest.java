package com.neobank.neobank.transaction.history;

import com.neobank.neobank.card.payment.CardPurchaseDetails;
import com.neobank.neobank.card.payment.CardRefundDetails;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.CardPurchaseHistoryResponse;
import com.neobank.neobank.transaction.history.dto.CardRefundHistoryResponse;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionHistoryMapperTest {

    @ParameterizedTest
    @MethodSource("typeAndDirectionVaried")
    void hidesRequestedFieldsOnlyForIncomingTransfers(
            TransactionType type,
            EntryDirection direction,
            boolean shouldHideRequestedFields
    ) {

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                type,
                "22222222222222222222222222222222",
                null
        );

        LedgerEntry entry = transaction.addEntry(
                "33333333333333333333333333333333",
                new BigDecimal("4000.00"),
                new BigDecimal("5000.00"),
                direction,
                ledgerAccount
        );

        ReflectionTestUtils.setField(entry, "createdAt", Instant.parse("2026-09-16T12:00:00Z"));

        TransactionHistoryItem item =
                TransactionHistoryMapper.toResponse(entry, "12345678901234", Map.of(), Map.of());

        assertThat(item)
                .isInstanceOf(TransactionHistoryResponse.class);

        TransactionHistoryResponse result = (TransactionHistoryResponse) item;

        if(shouldHideRequestedFields) {
            assertThat(result.requestedAmount())
                    .isNull();

            assertThat(result.requestedCurrency())
                    .isNull();
        } else {
            assertThat(result.requestedAmount())
                    .isEqualByComparingTo(transaction.getRequestedAmount());

            assertThat(result.requestedCurrency())
                    .isEqualTo(transaction.getRequestedCurrency());
        }

        assertThat(result.accountNumber())
                .isEqualTo("12345678901234");

        assertThat(result.entryReference())
                .isEqualTo(entry.getReference());

        assertThat(result.transactionReference())
                .isEqualTo(transaction.getReference());

        assertThat(result.transactionType())
                .isSameAs(type);

        assertThat(result.direction())
                .isSameAs(direction);

        assertThat(result.appliedAmount())
                .isEqualByComparingTo(entry.getAmount());

        assertThat(result.accountCurrency())
                .isSameAs(ledgerAccount.getCurrency());

        assertThat(result.balanceAfter())
                .isEqualByComparingTo(entry.getBalanceAfter());

        assertThat(result.createdAt())
                .isEqualTo(entry.getCreatedAt());
    }

    @Test
    void mapsPayerCardPurchaseEntryToCardPurchaseResponse() {
        LedgerAccount fundingLedgerAccount = LedgerAccount.createNew(
                "1".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        LedgerAccount merchantLedgerAccount = LedgerAccount.createNew(
                "2".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                TransactionType.CARD_PURCHASE,
                "3".repeat(32),
                null
        );

        ReflectionTestUtils.setField(transaction, "id", 1L);

        LedgerEntry payerEntry = transaction.addEntry(
                "4".repeat(32),
                new BigDecimal("250.00"),
                new BigDecimal("750.00"),
                EntryDirection.DEBIT,
                fundingLedgerAccount
        );

        transaction.addEntry(
                "5".repeat(32),
                new BigDecimal("250.00"),
                new BigDecimal("250.00"),
                EntryDirection.CREDIT,
                merchantLedgerAccount
        );

        transaction.complete();

        ReflectionTestUtils.setField(payerEntry, "createdAt", Instant.parse("2026-09-16T12:00:00Z"));

        CardPurchaseDetails cardPurchaseDetails = CardPurchaseDetails.createNew(
                transaction,
                "Test merchant",
                "6".repeat(32),
                "1234"
        );

        TransactionHistoryItem item = TransactionHistoryMapper.toResponse(
                payerEntry,
                "12".repeat(7),
                Map.of(1L, cardPurchaseDetails),
                Map.of()
        );

        assertThat(item)
                .isInstanceOf(CardPurchaseHistoryResponse.class);

        CardPurchaseHistoryResponse result = (CardPurchaseHistoryResponse) item;

        assertThat(result.accountNumber())
                .isEqualTo("12".repeat(7));

        assertThat(result.entryReference())
                .isEqualTo(payerEntry.getReference());

        assertThat(result.transactionReference())
                .isEqualTo("3".repeat(32));

        assertThat(result.transactionType())
                .isSameAs(TransactionType.CARD_PURCHASE);

        assertThat(result.direction())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(result.appliedAmount())
                .isEqualByComparingTo(payerEntry.getAmount());

        assertThat(result.accountCurrency())
                .isSameAs(fundingLedgerAccount.getCurrency());

        assertThat(result.requestedAmount())
                .isEqualByComparingTo(transaction.getRequestedAmount());

        assertThat(result.requestedCurrency())
                .isSameAs(transaction.getRequestedCurrency());

        assertThat(result.balanceAfter())
                .isEqualByComparingTo(payerEntry.getBalanceAfter());

        assertThat(result.createdAt())
                .isEqualTo(payerEntry.getCreatedAt());

        assertThat(result.merchantName())
                .isEqualTo("Test merchant");

        assertThat(result.cardReference())
                .isEqualTo("6".repeat(32));

        assertThat(result.cardLastFourDigits())
                .isEqualTo("1234");
    }

    @Test
    void toResponseThrowsWhenPayerCardPurchaseDetailsAreMissing() {
        LedgerAccount fundingLedgerAccount = LedgerAccount.createNew(
                "1".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                TransactionType.CARD_PURCHASE,
                "3".repeat(32),
                null
        );

        ReflectionTestUtils.setField(transaction, "id", 1L);

        LedgerEntry payerEntry = transaction.addEntry(
                "4".repeat(32),
                new BigDecimal("250.00"),
                new BigDecimal("750.00"),
                EntryDirection.DEBIT,
                fundingLedgerAccount
        );

        assertThatThrownBy(() -> TransactionHistoryMapper.toResponse(payerEntry, "12".repeat(7), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Card purchase details not found for transaction " + "3".repeat(32));
    }

    @ParameterizedTest
    @MethodSource("cardPurchasePayerEntrySource")
    void isCardPurchasePayerEntryMatchesOnlyCardPurchaseDebits(
            TransactionType type,
            EntryDirection direction,
            boolean expectedResult
    ) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "1".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                type,
                "3".repeat(32),
                null
        );

        LedgerEntry entry = transaction.addEntry(
                "4".repeat(32),
                new BigDecimal("100.00"),
                new BigDecimal("900.00"),
                direction,
                ledgerAccount
        );

        assertThat(TransactionHistoryMapper.isCardPurchasePayerEntry(entry))
                .isEqualTo(expectedResult);
    }

    @Test
    void mapsCardRefundCreditToCardRefundResponseLinkedToItsPurchase() {
        LedgerAccount fundingLedgerAccount = LedgerAccount.createNew("1".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY);
        LedgerAccount merchantLedgerAccount = LedgerAccount.createNew("2".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY);

        BankTransaction purchase = BankTransaction.createNew(
                new BigDecimal("250.00"), CurrencyCode.TRY, TransactionType.CARD_PURCHASE, "3".repeat(32), null
        );
        purchase.addEntry("4".repeat(32), new BigDecimal("250.00"), new BigDecimal("750.00"), EntryDirection.DEBIT, fundingLedgerAccount);
        purchase.addEntry("5".repeat(32), new BigDecimal("250.00"), new BigDecimal("250.00"), EntryDirection.CREDIT, merchantLedgerAccount);
        purchase.complete();

        CardPurchaseDetails cardPurchaseDetails = CardPurchaseDetails.createNew(purchase, "Test merchant", "6".repeat(32), "1234");
        purchase.markRefunded();

        BankTransaction refund = BankTransaction.createNew(
                new BigDecimal("250.00"), CurrencyCode.TRY, TransactionType.CARD_REFUND, "7".repeat(32), null
        );
        ReflectionTestUtils.setField(refund, "id", 2L);
        refund.addEntry("8".repeat(32), new BigDecimal("250.00"), new BigDecimal("0.00"), EntryDirection.DEBIT, merchantLedgerAccount);
        LedgerEntry refundCredit = refund.addEntry("9".repeat(32), new BigDecimal("250.00"), new BigDecimal("1000.00"), EntryDirection.CREDIT, fundingLedgerAccount);
        refund.complete();

        ReflectionTestUtils.setField(refundCredit, "createdAt", Instant.parse("2026-09-17T12:00:00Z"));

        CardRefundDetails cardRefundDetails = CardRefundDetails.createNew(refund, cardPurchaseDetails);

        TransactionHistoryItem item = TransactionHistoryMapper.toResponse(
                refundCredit,
                "12".repeat(7),
                Map.of(),
                Map.of(2L, cardRefundDetails)
        );

        assertThat(item)
                .isInstanceOf(CardRefundHistoryResponse.class);

        CardRefundHistoryResponse result = (CardRefundHistoryResponse) item;

        assertThat(result.accountNumber()).isEqualTo("12".repeat(7));
        assertThat(result.entryReference()).isEqualTo("9".repeat(32));
        assertThat(result.transactionReference()).isEqualTo("7".repeat(32));
        assertThat(result.transactionType()).isSameAs(TransactionType.CARD_REFUND);
        assertThat(result.direction()).isSameAs(EntryDirection.CREDIT);
        assertThat(result.appliedAmount()).isEqualByComparingTo("250.00");
        assertThat(result.accountCurrency()).isSameAs(CurrencyCode.TRY);
        assertThat(result.requestedAmount()).isEqualByComparingTo("250.00");
        assertThat(result.requestedCurrency()).isSameAs(CurrencyCode.TRY);
        assertThat(result.balanceAfter()).isEqualByComparingTo("1000.00");
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-09-17T12:00:00Z"));
        assertThat(result.merchantName()).isEqualTo("Test merchant");
        assertThat(result.cardReference()).isEqualTo("6".repeat(32));
        assertThat(result.cardLastFourDigits()).isEqualTo("1234");
        assertThat(result.purchaseReference())
                .as("links the refund row to the purchase row's transactionReference")
                .isEqualTo("3".repeat(32));
    }

    @Test
    void toResponseThrowsWhenCardRefundDetailsAreMissing() {
        LedgerAccount fundingLedgerAccount = LedgerAccount.createNew("1".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY);

        BankTransaction refund = BankTransaction.createNew(
                new BigDecimal("250.00"), CurrencyCode.TRY, TransactionType.CARD_REFUND, "7".repeat(32), null
        );
        ReflectionTestUtils.setField(refund, "id", 2L);

        LedgerEntry refundCredit = refund.addEntry(
                "9".repeat(32), new BigDecimal("250.00"), new BigDecimal("1000.00"), EntryDirection.CREDIT, fundingLedgerAccount
        );

        assertThatThrownBy(() -> TransactionHistoryMapper.toResponse(refundCredit, "12".repeat(7), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Card refund details not found for transaction " + "7".repeat(32));
    }

    @ParameterizedTest
    @MethodSource("cardRefundReceiverEntrySource")
    void isCardRefundReceiverEntryMatchesOnlyCardRefundCredits(
            TransactionType type,
            EntryDirection direction,
            boolean expectedResult
    ) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew("1".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY);

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"), CurrencyCode.TRY, type, "3".repeat(32), null
        );

        LedgerEntry entry = transaction.addEntry(
                "4".repeat(32), new BigDecimal("100.00"), new BigDecimal("900.00"), direction, ledgerAccount
        );

        assertThat(TransactionHistoryMapper.isCardRefundReceiverEntry(entry))
                .isEqualTo(expectedResult);
    }

    static Stream<Arguments> cardRefundReceiverEntrySource() {
        return Stream.of(
                Arguments.of(TransactionType.CARD_REFUND, EntryDirection.CREDIT, true),
                Arguments.of(TransactionType.CARD_REFUND, EntryDirection.DEBIT, false),
                Arguments.of(TransactionType.CARD_PURCHASE, EntryDirection.CREDIT, false),
                Arguments.of(TransactionType.DEPOSIT, EntryDirection.CREDIT, false)
        );
    }

    static Stream<Arguments> typeAndDirectionVaried() {
        return Stream.of(
                Arguments.of(TransactionType.DEPOSIT, EntryDirection.CREDIT, false),

                Arguments.of(TransactionType.TRANSFER, EntryDirection.CREDIT, true),

                Arguments.of(TransactionType.TRANSFER, EntryDirection.DEBIT, false),

                Arguments.of(TransactionType.WITHDRAWAL, EntryDirection.DEBIT, false),

                Arguments.of(TransactionType.CARD_PURCHASE, EntryDirection.CREDIT, false),

                Arguments.of(TransactionType.CARD_REFUND, EntryDirection.DEBIT, false)
        );
    }

    static Stream<Arguments> cardPurchasePayerEntrySource() {
        return Stream.of(
                Arguments.of(TransactionType.CARD_PURCHASE, EntryDirection.DEBIT, true),
                Arguments.of(TransactionType.CARD_PURCHASE, EntryDirection.CREDIT, false),
                Arguments.of(TransactionType.TRANSFER, EntryDirection.DEBIT, false),
                Arguments.of(TransactionType.WITHDRAWAL, EntryDirection.DEBIT, false)
        );
    }
}
