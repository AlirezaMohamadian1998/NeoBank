package com.neobank.neobank.account;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AccountTest {

    private static final String VALID_ACCOUNT_NUMBER = "12345678901234";
    private static final String VALID_LEDGER_REFERENCE =
            "0123456789abcdef0123456789abcdef";

    private Customer customer;

    @BeforeEach
    void setUp() {
        customer = mock(Customer.class);
    }

    @Test
    void validCreationPreservesAllFields() {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                "Private Account",
                AccountType.CURRENT,
                customer,
                CurrencyCode.TRY
        );

        assertThat(account.getAccountNumber())
                .isEqualTo(VALID_ACCOUNT_NUMBER);

        assertThat(account.getName())
                .isEqualTo("Private Account");

        assertThat(account.getAccountType())
                .isEqualTo(AccountType.CURRENT);

        assertThat(account.getCustomer())
                .isSameAs(customer);

        assertThat(account.getLedgerAccount().getLedgerReference())
                .isEqualTo(VALID_LEDGER_REFERENCE);
    }

    @Test
    void createdLedgerIsAnActiveLiabilityInTheAccountCurrency() {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                "Private Account",
                AccountType.CURRENT,
                customer,
                CurrencyCode.USD
        );

        assertThat(account.getLedgerAccount().getType())
                .isEqualTo(LedgerAccountType.LIABILITY);

        assertThat(account.getLedgerAccount().getStatus())
                .isEqualTo(LedgerAccountStatus.ACTIVE);

        assertThat(account.getLedgerAccount().getCurrency())
                .isEqualTo(CurrencyCode.USD);
    }

    @Test
    void changingLedgerBalanceIsReflectedThroughAccountBalance() {
        Account account = createAccount();

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        account.getLedgerAccount().credit(new BigDecimal("100.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        account.getLedgerAccount().debit(new BigDecimal("40.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }


    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1234567890123", "123456789012345", "1234567890123a"})
    void invalidAccountNumberIsRejected(String accountNumber) {
        assertThatThrownBy(() -> Account.createNew(
                accountNumber,
                VALID_LEDGER_REFERENCE,
                "Private Account",
                AccountType.CURRENT,
                customer,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Account number must be exactly 14 numeric characters"
                );
    }

    @Test
    void nullAccountTypeIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                "Private Account",
                null,
                customer,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account type cannot be null");
    }

    @Test
    void nullCustomerIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                "Private Account",
                AccountType.CURRENT,
                null,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer cannot be null");
    }

    @Test
    void nameIsTrimmed() {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                "   Private Account   ",
                AccountType.CURRENT,
                customer,
                CurrencyCode.TRY
        );

        assertThat(account.getName())
                .isEqualTo("Private Account");
    }


    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "     ")
    void missingNameBecomesNull(String name) {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                name,
                AccountType.CURRENT,
                customer,
                CurrencyCode.TRY
        );

        assertThat(account.getName())
                .isNull();
    }

    @Test
    void nameWith80CharactersIsAccepted() {
        String name = "a".repeat(80);

        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                name,
                AccountType.CURRENT,
                customer,
                CurrencyCode.TRY
        );

        assertThat(account.getName())
                .isEqualTo(name);

        assertThat(account.getName())
                .hasSize(80);
    }

    @Test
    void nameLongerThan80CharactersIsRejected() {
        String name = "a".repeat(81);

        assertThatThrownBy(() -> Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                name,
                AccountType.CURRENT,
                customer,
                CurrencyCode.TRY
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Account name cannot exceed 80 characters"
                );
    }

    private Account createAccount() {
        return Account.createNew(
                VALID_ACCOUNT_NUMBER,
                VALID_LEDGER_REFERENCE,
                "Private Account",
                AccountType.CURRENT,
                customer,
                CurrencyCode.TRY
        );
    }
}