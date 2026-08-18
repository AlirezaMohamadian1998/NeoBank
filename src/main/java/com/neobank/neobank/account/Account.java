package com.neobank.neobank.account;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@AllArgsConstructor(access = lombok.AccessLevel.PRIVATE)
@Table(name = "accounts")
public class Account extends BaseEntity {

    @Column(nullable = false, unique = true, updatable = false, length = 14)
    private String accountNumber;

    @Column(length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType accountType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.PERSIST, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false, updatable = false, unique = true)
    private LedgerAccount ledgerAccount;

    public static Account createNew(
            String accountNumber,
            String name,
            AccountType type,
            Customer customer,
            LedgerAccount ledgerAccount
    ) {
        if (accountNumber == null || !accountNumber.matches("[0-9]{14}")) {
            throw new IllegalArgumentException(
                    "Account number must be exactly 14 numeric characters"
            );
        }

        if (type == null) {
            throw new IllegalArgumentException("Account type cannot be null");
        }

        if (customer == null) {
            throw new IllegalArgumentException("Customer cannot be null");
        }

        if (ledgerAccount == null) {
            throw new IllegalArgumentException("Ledger account cannot be null");
        }

        if (ledgerAccount.getType() != LedgerAccountType.LIABILITY) {
            throw new IllegalArgumentException("Customer account must use a liability ledger account");
        }

        if (ledgerAccount.getStatus() != LedgerAccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Customer account cannot use a closed ledger account");
        }

        String normalizedName = null;

        if (name != null) {
            normalizedName = name.trim();

            if (normalizedName.isBlank()) {
                normalizedName = null;
            } else if (normalizedName.length() > 80) {
                throw new IllegalArgumentException(
                        "Account name cannot exceed 80 characters"
                );
            }
        }

        return new Account(
                accountNumber,
                normalizedName,
                type,
                customer,
                ledgerAccount
        );
    }

    @Transient
    public BigDecimal getBalance() {
        return ledgerAccount.getBalance();
    }

    @Transient
    public CurrencyCode getCurrency() {
        return ledgerAccount.getCurrency();
    }
}
