package com.neobank.neobank.creditaccount;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "credit_accounts")
public class CreditAccount extends BaseEntity {

    @Column(nullable = false, unique = true, updatable = false, length = 14)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.PERSIST, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false, updatable = false, unique = true)
    private LedgerAccount ledgerAccount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal creditLimit;

    private CreditAccount(String accountNumber, Customer customer, LedgerAccount ledgerAccount, BigDecimal creditLimit) {
        this.accountNumber = accountNumber;
        this.customer = customer;
        this.ledgerAccount = ledgerAccount;
        this.creditLimit = creditLimit;
    }

    public static CreditAccount createNew(
            String accountNumber,
            String ledgerReference,
            Customer customer,
            CurrencyCode currency,
            BigDecimal creditLimit
    ) {
        if(accountNumber == null || !accountNumber.matches("[0-9]{14}")) {
            throw new IllegalArgumentException("Account number must be exactly 14 numeric characters");
        }

        if(customer == null) {
            throw new IllegalArgumentException("Customer cannot be null");
        }

        if(creditLimit == null) {
            throw new IllegalArgumentException("Credit limit cannot be null");
        }

        if(creditLimit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit limit must be greater than zero");
        }

        if(creditLimit.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Credit limit must not have more than 2 decimal places");
        }

        LedgerAccount ledgerAccount = LedgerAccount.createNewWithNegativeBalanceAllowed(
                ledgerReference,
                LedgerAccountType.ASSET,
                currency
        );

        return new CreditAccount(
                accountNumber,
                customer,
                ledgerAccount,
                creditLimit.setScale(2, RoundingMode.UNNECESSARY)
        );
    }

    public CurrencyCode getCurrency() {
        return ledgerAccount.getCurrency();
    }

    public BigDecimal getAvailableCredit() {
        return creditLimit.subtract(ledgerAccount.getBalance());
    }

    public void ensureWithinCreditLimit(BigDecimal amount) {
        if(amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }

        BigDecimal availableCredit = getAvailableCredit();

        if(amount.compareTo(availableCredit) > 0) {
            throw new CreditLimitExceededException(
                    "Amount exceeds the available credit of " + availableCredit.toPlainString()
            );
        }
    }

    public void ensureWithinOutstandingBalance(BigDecimal amount) {
        if(amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }

        BigDecimal outstandingBalance = ledgerAccount.getBalance().max(BigDecimal.ZERO).setScale(2, RoundingMode.UNNECESSARY);

        if(amount.compareTo(outstandingBalance) > 0) {
            throw new RepaymentExceedsBalanceException(
                    "Repayment exceeds the outstanding balance of " + outstandingBalance.toPlainString()
            );
        }
    }
}
