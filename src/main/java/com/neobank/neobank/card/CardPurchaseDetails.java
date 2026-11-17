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
@Table(name = "card_purchase_details")
public class CardPurchaseDetails extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(nullable = false, updatable = false, unique = true)
    private BankTransaction bankTransaction;

    @Column(nullable = false, updatable = false, length = 128)
    private String merchantName;

    @Column(
            nullable = false,
            updatable = false,
            length = 32,
            check = @CheckConstraint(constraint = "REGEXP_LIKE(card_reference, '^[0-9a-f]{32}$', 'c')")
    )
    private String cardReference;

    public static CardPurchaseDetails createNew(
            BankTransaction bankTransaction,
            String merchantName,
            String cardReference
    ) {
        if(bankTransaction == null) {
            throw new IllegalArgumentException("Bank transaction must not be null");
        }

        if(bankTransaction.getTransactionType() != TransactionType.DEBIT_CARD_PURCHASE) {
            throw new IllegalArgumentException("The transaction type is not correct");
        }

        if(bankTransaction.getStatus() != TransactionStatus.COMPLETED) {
            throw new IllegalArgumentException("Transaction must be completed");
        }

        if(merchantName == null) {
            throw new IllegalArgumentException("Merchant name must not be null");
        }

        if(merchantName.isBlank()) {
            throw new IllegalArgumentException("Merchant name must not be blank");
        }

        if(merchantName.length() > 128) {
            throw new IllegalArgumentException("Merchant name must not exceed 128 characters");
        }

        if(cardReference == null) {
            throw new IllegalArgumentException("Card reference must not be null");
        }

        if(!cardReference.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Card reference must be exactly 32 hexadecimal characters");
        }

        return new CardPurchaseDetails(
                bankTransaction,
                merchantName,
                cardReference
        );
    }
}
