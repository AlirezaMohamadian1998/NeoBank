package com.neobank.neobank.internalaccount;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @ParameterizedTest
    @CsvSource({
            "SETTLEMENT, LIABILITY, SETTLEMENT internal account requires a ledger account of type ASSET",
            "SETTLEMENT, INCOME, SETTLEMENT internal account requires a ledger account of type ASSET",
            "INTEREST_INCOME, ASSET, INTEREST_INCOME internal account requires a ledger account of type INCOME",
            "INTEREST_INCOME, LIABILITY, INTEREST_INCOME internal account requires a ledger account of type INCOME"
    })
    void purposeRejectsLedgerAccountOfTheWrongType(
            InternalAccountPurpose purpose,
            LedgerAccountType ledgerAccountType,
            String message
    ) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                ledgerAccountType,
                CurrencyCode.TRY
        );

        assertThatThrownBy(() -> InternalAccount.createNew(purpose, ledgerAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
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
