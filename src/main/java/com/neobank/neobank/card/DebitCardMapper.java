package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.dto.CardRefundResponse;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.LedgerEntry;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DebitCardMapper {
    public static DebitCardIssueResponse toIssueDebitCardResponse(DebitCard debitCard, String fundingAccountNumber) {
        return new DebitCardIssueResponse(
                debitCard.getCardReference(),
                debitCard.getExpirationYearMonth(),
                fundingAccountNumber,
                debitCard.getLastFourDigits()
        );
    }

    public static DebitCardRetrieveResponse toRetrieveDebitCardResponse(DebitCard debitCard, String fundingAccountNumber) {
        return new DebitCardRetrieveResponse(
                debitCard.getCardReference(),
                debitCard.getLastFourDigits(),
                debitCard.getStatus(),
                debitCard.getExpirationYearMonth(),
                fundingAccountNumber
        );
    }

    public static CardPurchaseResponse toCardPurchaseResponse(
            DebitCard debitCard,
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
                debitCard.getLastFourDigits(),
                debitCard.getCardReference(),
                debitCard.getFundingAccount().getAccountNumber(),
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
