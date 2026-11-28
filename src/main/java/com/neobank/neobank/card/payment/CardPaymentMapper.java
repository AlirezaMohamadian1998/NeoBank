package com.neobank.neobank.card.payment;

import com.neobank.neobank.card.Card;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.card.payment.dto.CardRefundResponse;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.LedgerEntry;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CardPaymentMapper {
    public static CardPurchaseResponse toCardPurchaseResponse(
            Card card,
            CardPurchaseDetails cardPurchaseDetails,
            LedgerEntry sourceEntry
    ) {
        BankTransaction transaction = cardPurchaseDetails.getBankTransaction();

        return new CardPurchaseResponse(
                transaction.getReference(),
                transaction.getRequestedAmount(),
                sourceEntry.getAmount(),
                transaction.getRequestedCurrency(),
                sourceEntry.getCurrency(),
                sourceEntry.getBalanceAfter(),
                cardPurchaseDetails.getMerchantName(),
                card.getLastFourDigits(),
                card.getCardReference(),
                card.getLinkedAccountNumber(),
                transaction.getCreatedAt()
        );
    }

    public static CardRefundResponse toCardRefundResponse(
            CardRefundDetails cardRefundDetails,
            LedgerEntry merchantEntry,
            String merchantAccountNumber
    ) {
        BankTransaction refundTransaction = cardRefundDetails.getBankTransaction();
        CardPurchaseDetails cardPurchaseDetails = cardRefundDetails.getCardPurchaseDetails();

        return new CardRefundResponse(
                refundTransaction.getReference(),
                cardPurchaseDetails.getBankTransaction().getReference(),
                refundTransaction.getRequestedAmount(),
                refundTransaction.getRequestedCurrency(),
                merchantEntry.getAmount(),
                merchantEntry.getCurrency(),
                merchantEntry.getBalanceAfter(),
                cardPurchaseDetails.getMerchantName(),
                merchantAccountNumber,
                cardPurchaseDetails.getCardLastFourDigits(),
                refundTransaction.getCreatedAt()
        );
    }
}
