package com.neobank.neobank.idempotency;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.shared.persistence.BaseEntity;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.TransactionStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Table(name = "idempotency_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class IdempotencyRecord extends BaseEntity {

    @Column(nullable = false, updatable = false, length = 32)
    private String idempotencyKey;

    @Column(nullable = false, updatable = false, length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bank_transaction_id", nullable = false, updatable = false)
    private BankTransaction bankTransaction;

    public static IdempotencyRecord createNew(
            @NonNull String idempotencyKey,
            @NonNull String requestHash,
            @NonNull Customer customer,
            @NonNull BankTransaction bankTransaction
    ) {
        if(bankTransaction.getStatus() != TransactionStatus.COMPLETED) {
            throw new IllegalArgumentException("Transaction status must be COMPLETED");
        }

        if(!requestHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Request hash must contain only hex characters [a-f, 0-9] and be exactly 64 characters long");
        }

        if(!idempotencyKey.matches("[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Idempotency key must contain only hex characters [a-f, 0-9] and be exactly 32 characters long");
        }

        return new IdempotencyRecord(
                idempotencyKey,
                requestHash,
                customer,
                bankTransaction
        );
    }
}
