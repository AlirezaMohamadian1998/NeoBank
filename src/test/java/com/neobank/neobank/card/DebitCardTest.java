package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.YearMonth;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebitCardTest {

    @Test
    void validCreationPreservesFieldsAndFundingAccount() {
        String reference = "0123456789abcdef0123456789abcdef";
        String lastFourDigits = "1234";
        YearMonth expirationYearMonth = YearMonth.of(2029, 9);
        YearMonth currentYearMonth = YearMonth.of(2026, 9);
        Account fundingAccount = createAccount(AccountType.CURRENT);

        DebitCard debitCard = DebitCard.createNew(
                reference,
                lastFourDigits,
                expirationYearMonth,
                currentYearMonth,
                fundingAccount
        );

        assertThat(debitCard.getCardReference())
                .isEqualTo(reference);

        assertThat(debitCard.getLastFourDigits())
                .isEqualTo(lastFourDigits);

        assertThat(debitCard.getExpirationYearMonth())
                .isEqualTo(expirationYearMonth);

        assertThat(debitCard.getStatus())
                .isEqualTo(CardStatus.INACTIVE);

        assertThat(debitCard.getFundingAccount())
                .isSameAs(fundingAccount);
    }

    @ParameterizedTest
    @MethodSource("ineligibleFundingAccountsProvider")
    void ineligibleFundingAccountsAreRejected(
            Account fundingAccount,
            String exceptionMessage
    ) {
        assertThatThrownBy(() -> DebitCard.createNew(
                "0123456789abcdef0123456789abcdef",
                "1234",
                YearMonth.of(2029, 9),
                YearMonth.of(2026, 9),
                fundingAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(exceptionMessage);
    }

    static Stream<Arguments> ineligibleFundingAccountsProvider() {
        Account closedAccount = createAccount(AccountType.CURRENT);
        closedAccount.getLedgerAccount().close();

        return Stream.of(
                Arguments.of(
                        null,
                        "Account cannot be null"
                ),
                Arguments.of(
                        createAccount(AccountType.SAVINGS),
                        "Account must be type current."
                ),
                Arguments.of(
                        closedAccount,
                        "Account must be active."
                )
        );
    }

    private static Account createAccount(AccountType accountType) {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        return Account.createNew(
                "12345678901234",
                "Primary account",
                accountType,
                customer,
                ledgerAccount
        );
    }
}
