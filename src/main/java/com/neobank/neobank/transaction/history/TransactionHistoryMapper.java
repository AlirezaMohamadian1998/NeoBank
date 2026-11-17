package com.neobank.neobank.transaction.history;

import com.neobank.neobank.card.CardPurchaseDetails;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.CardPurchaseHistoryResponse;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;

import java.util.Map;

public class TransactionHistoryMapper {
    public static TransactionHistoryItem toResponse(
            LedgerEntry entry,
            String accountNumber,
            Map<Long, CardPurchaseDetails> cardPurchaseDetailsByTransactionId
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

        boolean isTransferOperationReceiver =
                (entry.getBankTransaction().getTransactionType() == TransactionType.TRANSFER)
                        &&
                        (entry.getDirection() == EntryDirection.CREDIT);

        return new TransactionHistoryResponse(
                accountNumber,
                entry.getReference(),
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

    private static CardPurchaseHistoryResponse toCardPurchaseResponse(
            LedgerEntry entry,
            String accountNumber,
            CardPurchaseDetails cardPurchaseDetails
    ) {
        return new CardPurchaseHistoryResponse(
                accountNumber,
                entry.getReference(),
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
}
