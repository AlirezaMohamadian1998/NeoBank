package com.neobank.neobank.card;

import com.neobank.neobank.shared.persistence.BaseEntity;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.TransactionStatus;
import com.neobank.neobank.transaction.TransactionType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "card_refund_details")
public class CardRefundDetails extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(nullable = false, updatable = false, unique = true)
    private BankTransaction bankTransaction;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, updatable = false, unique = true)
    private CardPurchaseDetails cardPurchaseDetails;

    public static CardRefundDetails createNew(
            BankTransaction bankTransaction,
            CardPurchaseDetails cardPurchaseDetails
    ) {
        if(bankTransaction == null) {
            throw new IllegalArgumentException("Bank transaction must not be null");
        }

        if(bankTransaction.getTransactionType() != TransactionType.CARD_REFUND) {
            throw new IllegalArgumentException("The transaction type is not correct");
        }

        if(bankTransaction.getStatus() != TransactionStatus.COMPLETED) {
            throw new IllegalArgumentException("Transaction must be completed");
        }

        if(cardPurchaseDetails == null) {
            throw new IllegalArgumentException("Card purchase details must not be null");
        }

        if(cardPurchaseDetails.getBankTransaction().getStatus() != TransactionStatus.REFUNDED) {
            throw new IllegalArgumentException("Original purchase must be marked as refunded");
        }

        return new CardRefundDetails(bankTransaction, cardPurchaseDetails);
    }
}
