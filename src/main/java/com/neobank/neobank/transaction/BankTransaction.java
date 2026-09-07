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

    @Column(updatable = false)
    private String note;

    @OneToMany(mappedBy = "bankTransaction", fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "fx_info_id", updatable = false, unique = true)
    private FxInfo fxInfo;

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
                new ArrayList<>(),
                null
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

    public void addFxInfo(FxInfo fxInfo) {
        if(status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Transaction is not pending");
        }

        if(fxInfo == null) {
            throw new IllegalArgumentException("Fx info must not be null");
        }

        if(this.fxInfo != null) {
            throw new IllegalStateException("Fx info is already attached");
        }

        if(getFxRate(fxInfo, CurrencyContext.REQUEST).getCurrency() != requestedCurrency) {
            throw new IllegalArgumentException("The currency of element with REQUEST currency context must match the requested currency");
        }

        this.fxInfo = fxInfo;
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

        if(!containsDifferentCurrency && fxInfo != null) {
            throw new IllegalStateException("Fx info must be null when the currencies are same");
        }

        LedgerEntry debitEntry = getEntry(EntryDirection.DEBIT);
        LedgerEntry creditEntry = getEntry(EntryDirection.CREDIT);

        if(containsDifferentCurrency) {
            if(fxInfo == null) {
                throw new IllegalStateException("Fx info must not be null when the currencies differ");
            }

            if(getFxRate(fxInfo, CurrencyContext.SOURCE).getCurrency() != debitEntry.getCurrency()) {
                throw new IllegalStateException("Debit entry currency must match the SOURCE FX currency");
            }

            if(getFxRate(fxInfo, CurrencyContext.DESTINATION).getCurrency() != creditEntry.getCurrency()) {
                throw new IllegalStateException("Credit entry currency must match the DESTINATION FX currency");
            }
        }

        BigDecimal sourceRate = fxInfo != null
                ? fxInfo.getRate(CurrencyContext.SOURCE)
                : BigDecimal.ONE;

        BigDecimal destinationRate = fxInfo != null
                ? fxInfo.getRate(CurrencyContext.DESTINATION)
                : BigDecimal.ONE;

        BigDecimal expectedDebitAmount = requestedAmount
                .multiply(sourceRate)
                .setScale(2, RoundingMode.HALF_EVEN);

        BigDecimal expectedCreditAmount = requestedAmount
                .multiply(destinationRate)
                .setScale(2, RoundingMode.HALF_EVEN);

        if (debitEntry.getAmount().compareTo(expectedDebitAmount) != 0) {
            throw new IllegalStateException("Debit entry amount does not match the expected amount");
        }

        if (creditEntry.getAmount().compareTo(expectedCreditAmount) != 0) {
            throw new IllegalStateException("Credit entry amount does not match the expected amount");
        }

        status = TransactionStatus.COMPLETED;
    }

    private FxRate getFxRate(FxInfo fxInfo, CurrencyContext currencyContext) {
        return fxInfo.getRates()
                .stream()
                .filter(fxRate -> fxRate.getCurrencyContext() == currencyContext)
                .findFirst()
                .orElseThrow();
    }

    private LedgerEntry getEntry(EntryDirection direction) {
        return entries.stream()
                .filter(entry -> entry.getDirection() == direction)
                .findFirst()
                .orElseThrow();
    }
}
