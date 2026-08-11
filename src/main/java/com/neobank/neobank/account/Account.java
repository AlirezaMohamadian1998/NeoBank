package com.neobank.neobank.account;

import com.neobank.neobank.customer.Customer;
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

    @Version
    private Long version;

    @Column(nullable = false, unique = true, updatable = false, length = 14)
    private String accountNumber;

    @Column(length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private CurrencyCode currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    public static Account createNew(
            String accountNumber,
            String name,
            AccountType type,
            CurrencyCode currency,
            Customer customer
    ) {
        if (accountNumber == null || !accountNumber.matches("[0-9]{14}")) {
            throw new IllegalArgumentException(
                    "Account number must be exactly 14 numeric characters"
            );
        }

        if (type == null) {
            throw new IllegalArgumentException("Account type cannot be null");
        }

        if (currency == null) {
            throw new IllegalArgumentException("Currency cannot be null");
        }

        if (customer == null) {
            throw new IllegalArgumentException("Customer cannot be null");
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
                null,
                accountNumber,
                normalizedName,
                type,
                currency,
                BigDecimal.ZERO.setScale(2),
                customer
        );
    }
}
