package com.neobank.neobank.transaction;

import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "bank_transactions")
public class BankTransaction extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType transactionType;

    @Column(nullable = false, unique = true, updatable = false, length = 32)
    private String reference;

    @Column(updatable = false, length = 255)
    private String note;

    public static BankTransaction createNew(TransactionType transactionType, String reference, String note) {
        if(reference == null) {
            throw new IllegalArgumentException("Reference cannot be null");
        }
        reference = reference.trim();
        if(reference.isBlank()) {
            throw new IllegalArgumentException("Reference cannot be empty");
        }
        if(reference.length() > 32) {
            throw new IllegalArgumentException("Reference cannot exceed 32 characters");
        }
        if(transactionType == null) {
            throw new IllegalArgumentException("Transaction type cannot be null");
        }

        String normalizedNote = null;

        if (note != null) {
            normalizedNote = note.trim();

            if (normalizedNote.isBlank()) {
                normalizedNote = null;
            } else if (normalizedNote.length() > 255) {
                throw new IllegalArgumentException(
                        "Transaction note cannot exceed 255 characters"
                );
            }
        }

        return new BankTransaction(transactionType, reference, normalizedNote);
    }
}
