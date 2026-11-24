package com.neobank.neobank.creditaccount;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

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

    @Column(nullable = false, updatable = false, precision = 6, scale = 4)
    private BigDecimal annualInterestRate;

    @Column(nullable = false)
    private LocalDate nextStatementDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payoff_account_id")
    private Account payoffAccount;

    private CreditAccount(
            String accountNumber,
            Customer customer,
            LedgerAccount ledgerAccount,
            BigDecimal creditLimit,
            BigDecimal annualInterestRate,
            LocalDate nextStatementDate
    ) {
        this.accountNumber = accountNumber;
        this.customer = customer;
        this.ledgerAccount = ledgerAccount;
        this.creditLimit = creditLimit;
        this.annualInterestRate = annualInterestRate;
        this.nextStatementDate = nextStatementDate;
    }

    public static CreditAccount createNew(
            String accountNumber,
            String ledgerReference,
            Customer customer,
            CurrencyCode currency,
            BigDecimal creditLimit,
            BigDecimal annualInterestRate,
            LocalDate openedOn
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

        if(annualInterestRate == null) {
            throw new IllegalArgumentException("Annual interest rate cannot be null");
        }

        if(annualInterestRate.compareTo(BigDecimal.ZERO) < 0 || annualInterestRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Annual interest rate must be at least 0 and less than 1");
        }

        if(annualInterestRate.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("Annual interest rate must not have more than 4 decimal places");
        }

        if(openedOn == null) {
            throw new IllegalArgumentException("Opening date cannot be null");
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
                creditLimit.setScale(2, RoundingMode.UNNECESSARY),
                annualInterestRate.setScale(4, RoundingMode.UNNECESSARY),
                openedOn.withDayOfMonth(Math.min(openedOn.getDayOfMonth(), 28)).plusMonths(1)
        );
    }

    public void advanceStatementDate() {
        nextStatementDate = nextStatementDate.plusMonths(1);
    }

    public void close(Account payoffAccount) {
        if(payoffAccount == null) {
            throw new IllegalArgumentException("Payoff account cannot be null");
        }

        ensureOpen();
        ledgerAccount.close();
        this.payoffAccount = payoffAccount;
    }

    public boolean isClosed() {
        return ledgerAccount.getStatus() == LedgerAccountStatus.CLOSED;
    }

    public void ensureOpen() {
        if(isClosed()) {
            throw new CreditAccountClosedException("Credit account is closed");
        }
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
    }}
