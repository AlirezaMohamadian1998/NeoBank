package com.neobank.neobank.card;

import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.YearMonth;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "credit_cards")
public class CreditCard extends Card {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_account_id", nullable = false, updatable = false)
    private CreditAccount creditAccount;

    private CreditCard(
            String cardReference,
            String lastFourDigits,
            YearMonth expiry,
            YearMonth now,
            CreditAccount creditAccount,
            CardSpendingLimits spendingLimits
    ) {
        super(cardReference, lastFourDigits, expiry, now, spendingLimits);

        this.creditAccount = creditAccount;
    }

    @Override
    public Customer getOwner() {
        return creditAccount.getCustomer();
    }

    @Override
    public LedgerAccount getLinkedLedgerAccount() {
        return creditAccount.getLedgerAccount();
    }

    @Override
    public String getLinkedAccountNumber() {
        return creditAccount.getAccountNumber();
    }

    public static CreditCard createNew(
            String reference,
            String lastFourDigits,
            YearMonth expiry,
            YearMonth now,
            CreditAccount creditAccount,
            CardSpendingLimits spendingLimits
    ) {
        if(creditAccount == null) {
            throw new IllegalArgumentException("Credit account cannot be null");
        }

        if(creditAccount.getLedgerAccount().getStatus() != LedgerAccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Credit account must be active.");
        }

        return new CreditCard(
                reference,
                lastFourDigits,
                expiry,
                now,
                creditAccount,
                spendingLimits
        );
    }
}
