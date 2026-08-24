package com.neobank.neobank.internalaccount;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Getter
@Table(name = "internal_accounts")
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InternalAccount extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "internal_account_purpose", nullable = false, updatable = false)
    private InternalAccountPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, updatable = false)
    private CurrencyCode currency;

    @OneToOne(fetch = FetchType.LAZY, optional = false, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "ledger_account_id", nullable = false, updatable = false, unique = true)
    private LedgerAccount ledgerAccount;

    public static InternalAccount createNew(
            @NonNull InternalAccountPurpose internalAccountPurpose,
            @NonNull LedgerAccount ledgerAccount
    ) {
        if (ledgerAccount.getType() != LedgerAccountType.ASSET) {
            throw new IllegalArgumentException("Internal account must use an asset ledger account");
        }

        if (ledgerAccount.getStatus() != LedgerAccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Internal account cannot use a closed ledger account");
        }

        return new InternalAccount(internalAccountPurpose, ledgerAccount.getCurrency(), ledgerAccount);
    }

    public BigDecimal getBalance() {
        return ledgerAccount.getBalance();
    }
}
