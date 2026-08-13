package com.neobank.neobank.transaction;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.CurrencyCode;
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
@Table(name = "account_entries")
public class AccountEntry extends BaseEntity {

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EntryDirection entryDirection;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CurrencyCode currency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private BankTransaction bankTransaction;

    public static AccountEntry createNew(
            BigDecimal amount,
            BigDecimal balanceAfter,
            EntryDirection entryDirection,
            Account account,
            BankTransaction bankTransaction
    ) {
        if(amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if(amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount must not have more than 2 decimal places");
        }
        if(balanceAfter == null || balanceAfter.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Balance after must not be less than zero");
        }
        if(balanceAfter.compareTo(BigDecimal.ZERO) != 0 && balanceAfter.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Balance after must not have more than 2 decimal places");
        }
        if(entryDirection == null) {
            throw new IllegalArgumentException("Entry direction cannot be null");
        }
        if(account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }
        if(bankTransaction == null) {
            throw new IllegalArgumentException("Bank transaction cannot be null");
        }

        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        balanceAfter = balanceAfter.setScale(2, RoundingMode.UNNECESSARY);

        return new AccountEntry(amount, balanceAfter, entryDirection, account.getCurrency(), account, bankTransaction);
    }
}
