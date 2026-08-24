package com.neobank.neobank.transaction;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "bank_transactions")
public class BankTransaction extends BaseEntity {

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal requestedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CurrencyCode requestedCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType transactionType;

    @Column(nullable = false, unique = true, updatable = false, length = 32)
    private String reference;

    @Column(updatable = false, length = 255)
    private String note;

    @OneToMany(mappedBy = "bankTransaction", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries;

    public List<LedgerEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public static BankTransaction createNew(
            BigDecimal requestedAmount,
            CurrencyCode requestedCurrency,
            TransactionType transactionType,
            String reference,
            String note
    ) {
        if (requestedAmount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }

        if (requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        if (requestedAmount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must not have more than 2 decimal places");
        }

        if (requestedCurrency == null) {
            throw new IllegalArgumentException("Currency cannot be null");
        }

        if (reference == null) {
            throw new IllegalArgumentException("Reference cannot be null");
        }

        if (!reference.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Reference must be exactly 32 hexadecimal characters");
        }

        if (transactionType == null) {
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
        requestedAmount = requestedAmount.setScale(2, RoundingMode.UNNECESSARY);

        return new BankTransaction(
                requestedAmount,
                requestedCurrency,
                TransactionStatus.PENDING,
                transactionType,
                reference,
                normalizedNote,
                new ArrayList<>()
        );
    }

    public LedgerEntry addEntry(
            String ledgerEntryReference,
            BigDecimal amount,
            BigDecimal balanceAfter,
            EntryDirection direction,
            LedgerAccount ledgerAccount
    ) {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Transaction is not pending");
        }

        LedgerEntry entry = LedgerEntry.createNew(
                ledgerEntryReference,
                amount,
                balanceAfter,
                direction,
                ledgerAccount,
                this
        );
        entries.add(entry);
        return entry;

    }

    public void complete() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Transaction is not pending");
        }

        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException(
                    "Transaction cannot be completed without entries"
            );
        }

        boolean containsDifferentCurrency = entries
                .stream()
                .anyMatch(entry ->
                        entry.getCurrency() != requestedCurrency
                );

        if (containsDifferentCurrency) {
            throw new IllegalStateException("All entries must use the transaction currency");
        }

        BigDecimal totalAmount =
                entries
                        .stream()
                        .map(entry ->
                                entry.getDirection() == EntryDirection.CREDIT
                                        ? entry.getAmount()
                                        : entry.getAmount().negate())
                        .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));

        if(totalAmount.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("Transaction debits and credits must balance");
        }

        status = TransactionStatus.COMPLETED;
    }
}
