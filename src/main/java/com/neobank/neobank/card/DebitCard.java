package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.YearMonth;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "debit_cards")
public class DebitCard extends Card {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account fundingAccount;

    private DebitCard(
            String cardReference,
            String lastFourDigits,
            YearMonth expiry,
            YearMonth now,
            Account account
    ) {
        super(cardReference, lastFourDigits, expiry, now);

        this.fundingAccount = account;
    }

    public static DebitCard createNew(
            String reference,
            String lastFourDigits,
            YearMonth expiry,
            YearMonth now,
            Account account
    ) {

        if(account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }

        if(account.getAccountType() != AccountType.CURRENT) {
            throw new IllegalArgumentException("Account must be type current.");
        }

        if(account.getLedgerAccount().getStatus() != LedgerAccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Account must be active.");
        }

        return new DebitCard(
                reference,
                lastFourDigits,
                expiry,
                now,
                account
        );
    }
}
