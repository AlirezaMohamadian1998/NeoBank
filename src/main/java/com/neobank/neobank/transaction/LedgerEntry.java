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

@Entity
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "ledger_entries")
public class LedgerEntry extends BaseEntity {
    @Column(nullable = false, updatable = false, unique = true, length = 32)
    private String reference;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EntryDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CurrencyCode currency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false, updatable = false)
    private LedgerAccount ledgerAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private BankTransaction bankTransaction;

    static LedgerEntry createNew(
            String reference,
            BigDecimal amount,
            BigDecimal balanceAfter,
            EntryDirection direction,
            LedgerAccount ledgerAccount,
            BankTransaction bankTransaction
    ) {
        if(reference == null) {
            throw new IllegalArgumentException("Reference must not be null");
        }

        if (!reference.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Reference must be exactly 32 hexadecimal characters");
        }

        if(amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }

        if(amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }

        if(amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must not have more than 2 decimal places");
        }

        if(balanceAfter == null) {
            throw new IllegalArgumentException("Balance after must not be null");
        }

        if(balanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Balance after must not be less than zero");
        }

        if(balanceAfter.compareTo(BigDecimal.ZERO) != 0 && balanceAfter.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Balance after must not have more than 2 decimal places");
        }

        if(direction == null) {
            throw new IllegalArgumentException("Entry direction cannot be null");
        }

        if(ledgerAccount == null) {
            throw new IllegalArgumentException("Ledger account cannot be null");
        }

        if(bankTransaction == null) {
            throw new IllegalArgumentException("Bank transaction cannot be null");
        }

        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        balanceAfter = balanceAfter.setScale(2, RoundingMode.UNNECESSARY);

        return new LedgerEntry(reference, amount, balanceAfter, direction, ledgerAccount.getCurrency(), ledgerAccount, bankTransaction);
    }
}
