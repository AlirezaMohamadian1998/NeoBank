package com.neobank.neobank.internalaccount;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

class InternalAccountTest {

    @Test
    void validCreationPreservesAllFields() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        InternalAccount internalAccount =
                InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount);

        assertThat(internalAccount.getBalance())
                .isEqualByComparingTo(ledgerAccount.getBalance());

        assertThat(internalAccount.getCurrency())
                .isSameAs(ledgerAccount.getCurrency());

        assertThat(internalAccount.getPurpose())
                .isSameAs(InternalAccountPurpose.SETTLEMENT);

        assertThat(internalAccount.getLedgerAccount())
                .isEqualTo(ledgerAccount);
    }

    @Test
    void settlementRejectsLiabilityLedgerAccount() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        assertThatThrownBy(() -> InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SETTLEMENT internal account requires a ledger account of type ASSET");
    }

    @Test
    void settlementRejectsIncomeLedgerAccount() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.INCOME,
                CurrencyCode.TRY
        );

        assertThatThrownBy(() -> InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SETTLEMENT internal account requires a ledger account of type ASSET");
    }

    @Test
    void interestIncomeAcceptsIncomeLedgerAccount() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.INCOME,
                CurrencyCode.USD
        );

        InternalAccount internalAccount =
                InternalAccount.createNew(InternalAccountPurpose.INTEREST_INCOME, ledgerAccount);

        assertThat(internalAccount.getPurpose())
                .isSameAs(InternalAccountPurpose.INTEREST_INCOME);

        assertThat(internalAccount.getCurrency())
                .isSameAs(CurrencyCode.USD);

        assertThat(internalAccount.getLedgerAccount())
                .isEqualTo(ledgerAccount);
    }

    @Test
    void interestIncomeRejectsAssetLedgerAccount() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assertThatThrownBy(() -> InternalAccount.createNew(InternalAccountPurpose.INTEREST_INCOME, ledgerAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INTEREST_INCOME internal account requires a ledger account of type INCOME");
    }

    @Test
    void interestIncomeRejectsLiabilityLedgerAccount() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        assertThatThrownBy(() -> InternalAccount.createNew(InternalAccountPurpose.INTEREST_INCOME, ledgerAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INTEREST_INCOME internal account requires a ledger account of type INCOME");
    }

    @Test
    void closedLedgerAccountIsRejected() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        ledgerAccount.close();

        assertThatThrownBy(() -> InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Internal account cannot use a closed ledger account");
    }

    @Test
    void currencyComesFromLedgerAccount() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        InternalAccount internalAccount =
                InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount);

        assertThat(internalAccount.getCurrency())
                .isSameAs(ledgerAccount.getCurrency());
    }

    @Test
    void balanceComesFromLedgerAccount() {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        BigDecimal balance = ledgerAccount.debit(new BigDecimal("100.00"));

        InternalAccount internalAccount =
                InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount);

        assertThat(internalAccount.getBalance())
                .isEqualByComparingTo(ledgerAccount.getBalance());

        assertThat(internalAccount.getBalance())
                .isEqualByComparingTo(balance);
    }
}
