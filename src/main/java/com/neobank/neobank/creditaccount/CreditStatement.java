package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.persistence.BaseEntity;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.TransactionType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Entity
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "credit_statements")
public class CreditStatement extends BaseEntity {

    @Column(nullable = false, updatable = false, unique = true, length = 32)
    private String statementReference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_account_id", nullable = false, updatable = false)
    private CreditAccount creditAccount;

    @Column(nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(nullable = false, updatable = false)
    private LocalDate statementDate;

    @Column(nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal closingBalance;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal averageDailyBalance;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal interestCharged;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal minimumPayment;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interest_transaction_id", updatable = false, unique = true)
    private BankTransaction interestTransaction;

    public static CreditStatement createNew(
            String statementReference,
            CreditAccount creditAccount,
            LocalDate periodStart,
            LocalDate statementDate,
            LocalDate dueDate,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal averageDailyBalance,
            BigDecimal interestCharged,
            BigDecimal minimumPayment,
            BankTransaction interestTransaction
    ) {
        if(statementReference == null || !statementReference.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Statement reference must be exactly 32 hexadecimal characters");
        }

        if(creditAccount == null) {
            throw new IllegalArgumentException("Credit account cannot be null");
        }

        if(periodStart == null || statementDate == null || dueDate == null) {
            throw new IllegalArgumentException("Statement dates cannot be null");
        }

        if(!periodStart.isBefore(statementDate)) {
            throw new IllegalArgumentException("Period start must be before the statement date");
        }

        if(!dueDate.isAfter(statementDate)) {
            throw new IllegalArgumentException("Due date must be after the statement date");
        }

        BigDecimal validatedOpeningBalance = validatedAmount(openingBalance, "Opening balance");
        BigDecimal validatedClosingBalance = validatedAmount(closingBalance, "Closing balance");
        BigDecimal validatedAverageDailyBalance = validatedNonNegativeAmount(averageDailyBalance, "Average daily balance");
        BigDecimal validatedInterestCharged = validatedNonNegativeAmount(interestCharged, "Interest charged");
        BigDecimal validatedMinimumPayment = validatedNonNegativeAmount(minimumPayment, "Minimum payment");

        if(validatedMinimumPayment.compareTo(validatedClosingBalance.max(BigDecimal.ZERO)) > 0) {
            throw new IllegalArgumentException("Minimum payment cannot exceed the closing balance");
        }

        boolean interestWasCharged = validatedInterestCharged.signum() > 0;

        if(interestWasCharged != (interestTransaction != null)) {
            throw new IllegalArgumentException("An interest transaction is required exactly when interest is charged");
        }

        if(interestTransaction != null && interestTransaction.getTransactionType() != TransactionType.CREDIT_CARD_INTEREST) {
            throw new IllegalArgumentException("Interest transaction must be of type CREDIT_CARD_INTEREST");
        }

        return new CreditStatement(
                statementReference,
                creditAccount,
                periodStart,
                statementDate,
                dueDate,
                validatedOpeningBalance,
                validatedClosingBalance,
                validatedAverageDailyBalance,
                validatedInterestCharged,
                validatedMinimumPayment,
                interestTransaction
        );
    }

    private static BigDecimal validatedNonNegativeAmount(BigDecimal amount, String fieldName) {
        BigDecimal validatedAmount = validatedAmount(amount, fieldName);

        if(validatedAmount.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " cannot be negative");
        }

        return validatedAmount;
    }

    private static BigDecimal validatedAmount(BigDecimal amount, String fieldName) {
        if(amount == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }

        if(amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(fieldName + " must not have more than 2 decimal places");
        }

        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
