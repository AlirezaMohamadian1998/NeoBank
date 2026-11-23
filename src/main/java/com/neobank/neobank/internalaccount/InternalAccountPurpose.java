package com.neobank.neobank.internalaccount;

import com.neobank.neobank.ledger.LedgerAccountType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum InternalAccountPurpose {
    SETTLEMENT(LedgerAccountType.ASSET),
    INTEREST_INCOME(LedgerAccountType.INCOME);

    private final LedgerAccountType ledgerType;
}
