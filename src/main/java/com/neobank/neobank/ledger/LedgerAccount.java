package com.neobank.neobank.ledger;

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
@Table(name = "ledger_accounts")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerAccount extends BaseEntity {

    @Version
    private Long version;

    @Column(nullable = false, unique = true, updatable = false, length = 32)
    private String ledgerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerAccountType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CurrencyCode currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LedgerAccountStatus status;

    public static LedgerAccount createNew(
            String ledgerReference,
            LedgerAccountType type,
            CurrencyCode currency
    ) {
        if(ledgerReference == null) {
            throw new IllegalArgumentException("Ledger reference cannot be null");
        }

        if (!ledgerReference.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Ledger reference must be exactly 32 hexadecimal characters");
        }

        if (type == null) {
            throw new IllegalArgumentException("Ledger account type cannot be null");
        }

        if (currency == null) {
            throw new IllegalArgumentException("Currency cannot be null");
        }

        return new LedgerAccount(
                null,
                ledgerReference,
                type,
                currency,
                BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY),
                LedgerAccountStatus.ACTIVE
        );
    }

    public BigDecimal credit(BigDecimal amount) {
        ensureActive();
        amount = validateAndNormalizeAmount(amount);

        return switch (type) {
            case LIABILITY -> increase(amount);
            case ASSET -> decrease(amount);
        };
    }

    public BigDecimal debit(BigDecimal amount) {
        ensureActive();
        amount = validateAndNormalizeAmount(amount);

        return switch (type) {
            case ASSET -> increase(amount);
            case LIABILITY -> decrease(amount);
        };
    }

    public void close() {
        ensureActive();

        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("Ledger account cannot be closed with a non-zero balance");
        }

        status = LedgerAccountStatus.CLOSED;
    }

    private BigDecimal increase(BigDecimal amount) {
        balance = balance.add(amount);
        return balance;
    }

    private BigDecimal decrease(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }

        balance = balance.subtract(amount);
        return balance;
    }

    private void ensureActive() {
        if (status != LedgerAccountStatus.ACTIVE) {
            throw new IllegalStateException("Ledger account is not active");
        }
    }

    private BigDecimal validateAndNormalizeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Amount must not be null");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Amount must be greater than zero"
            );
        }

        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    "Amount must not have more than 2 decimal places"
            );
        }

        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
