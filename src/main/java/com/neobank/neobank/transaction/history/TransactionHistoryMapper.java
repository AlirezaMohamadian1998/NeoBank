package com.neobank.neobank.transaction.history;

import com.neobank.neobank.card.CardPurchaseDetails;
import com.neobank.neobank.card.CardRefundDetails;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.CardPurchaseHistoryResponse;
import com.neobank.neobank.transaction.history.dto.CardRefundHistoryResponse;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;

import java.util.Map;

public class TransactionHistoryMapper {
    public static TransactionHistoryItem toResponse(
            LedgerEntry entry,
            String accountNumber,
            Map<Long, CardPurchaseDetails> cardPurchaseDetailsByTransactionId,
            Map<Long, CardRefundDetails> cardRefundDetailsByTransactionId
    ) {
        if(isCardPurchasePayerEntry(entry)) {
            CardPurchaseDetails cardPurchaseDetails = cardPurchaseDetailsByTransactionId.get(entry.getBankTransaction().getId());

            if(cardPurchaseDetails == null) {
                throw new IllegalStateException(
                        "Card purchase details not found for transaction " + entry.getBankTransaction().getReference()
                );
            }

            return toCardPurchaseResponse(entry, accountNumber, cardPurchaseDetails);
        }

        if(isCardRefundReceiverEntry(entry)) {
            CardRefundDetails cardRefundDetails = cardRefundDetailsByTransactionId.get(entry.getBankTransaction().getId());

            if(cardRefundDetails == null) {
                throw new IllegalStateException(
                        "Card refund details not found for transaction " + entry.getBankTransaction().getReference()
                );
            }

            return toCardRefundResponse(entry, accountNumber, cardRefundDetails);
        }

        boolean isTransferOperationReceiver =
                (entry.getBankTransaction().getTransactionType() == TransactionType.TRANSFER)
                        &&
                        (entry.getDirection() == EntryDirection.CREDIT);

        return new TransactionHistoryResponse(
                accountNumber,
                entry.getReference(),
                entry.getBankTransaction().getReference(),
                entry.getBankTransaction().getTransactionType(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getCurrency(),
                isTransferOperationReceiver ? null : entry.getBankTransaction().getRequestedAmount(),
                isTransferOperationReceiver ? null : entry.getBankTransaction().getRequestedCurrency(),
                entry.getBalanceAfter(),
                entry.getCreatedAt()
        );
    }

    public static boolean isCardPurchasePayerEntry(LedgerEntry entry) {
        return (entry.getBankTransaction().getTransactionType() == TransactionType.DEBIT_CARD_PURCHASE)
                &&
                (entry.getDirection() == EntryDirection.DEBIT);
    }

    public static boolean isCardRefundReceiverEntry(LedgerEntry entry) {
        return (entry.getBankTransaction().getTransactionType() == TransactionType.DEBIT_CARD_REFUND)
                &&
                (entry.getDirection() == EntryDirection.CREDIT);
    }

    private static CardPurchaseHistoryResponse toCardPurchaseResponse(
            LedgerEntry entry,
            String accountNumber,
            CardPurchaseDetails cardPurchaseDetails
    ) {
        return new CardPurchaseHistoryResponse(
                accountNumber,
                entry.getReference(),
                entry.getBankTransaction().getReference(),
                entry.getBankTransaction().getTransactionType(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getBankTransaction().getRequestedAmount(),
                entry.getBankTransaction().getRequestedCurrency(),
                entry.getBalanceAfter(),
                entry.getCreatedAt(),
                cardPurchaseDetails.getMerchantName(),
                cardPurchaseDetails.getCardReference(),
                cardPurchaseDetails.getCardLastFourDigits()
        );
    }

    private static CardRefundHistoryResponse toCardRefundResponse(
            LedgerEntry entry,
            String accountNumber,
            CardRefundDetails cardRefundDetails
    ) {
        CardPurchaseDetails cardPurchaseDetails = cardRefundDetails.getCardPurchaseDetails();

        return new CardRefundHistoryResponse(
                accountNumber,
                entry.getReference(),
                entry.getBankTransaction().getReference(),
                entry.getBankTransaction().getTransactionType(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getBankTransaction().getRequestedAmount(),
                entry.getBankTransaction().getRequestedCurrency(),
                entry.getBalanceAfter(),
                entry.getCreatedAt(),
                cardPurchaseDetails.getMerchantName(),
                cardPurchaseDetails.getCardReference(),
                cardPurchaseDetails.getCardLastFourDigits(),
                cardPurchaseDetails.getBankTransaction().getReference()
        );
    }
}
