package com.neobank.neobank.account;

import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AccountTest {

    private static final String VALID_ACCOUNT_NUMBER = "12345678901234";
    private static final String VALID_LEDGER_REFERENCE =
            "0123456789abcdef0123456789abcdef";

    private Customer customer;
    private LedgerAccount ledgerAccount;

    @BeforeEach
    void setUp() {
        customer = mock(Customer.class);

        ledgerAccount = LedgerAccount.createNew(
                VALID_LEDGER_REFERENCE,
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
    }

    @Test
    void validCreationPreservesAllFields() {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        assertThat(account.getAccountNumber())
                .isEqualTo(VALID_ACCOUNT_NUMBER);

        assertThat(account.getName())
                .isEqualTo("Private Account");

        assertThat(account.getAccountType())
                .isEqualTo(AccountType.CURRENT);

        assertThat(account.getCustomer())
                .isSameAs(customer);

        assertThat(account.getLedgerAccount())
                .isSameAs(ledgerAccount);
    }

    @Test
    void currencyComesFromLedgerAccount() {
        Account account = createAccount();

        assertThat(account.getCurrency())
                .isEqualTo(CurrencyCode.TRY);

        assertThat(account.getCurrency())
                .isEqualTo(ledgerAccount.getCurrency());
    }

    @Test
    void balanceComesFromLedgerAccount() {
        ledgerAccount.credit(new BigDecimal("125.50"));

        Account account = createAccount();

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("125.50"));

        assertThat(account.getBalance())
                .isEqualByComparingTo(ledgerAccount.getBalance());
    }

    @Test
    void changingLedgerBalanceIsReflectedThroughAccountBalance() {
        Account account = createAccount();

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        ledgerAccount.credit(new BigDecimal("100.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        ledgerAccount.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void nullAccountNumberIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                null,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Account number must be exactly 14 numeric characters"
                );
    }

    @Test
    void shortAccountNumberIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                "1234567890123",
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Account number must be exactly 14 numeric characters"
                );
    }

    @Test
    void longAccountNumberIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                "123456789012345",
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Account number must be exactly 14 numeric characters"
                );
    }

    @Test
    void nonNumericAccountNumberIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                "1234567890123a",
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
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
                "Private Account",
                null,
                customer,
                ledgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Account type cannot be null");
    }

    @Test
    void nullCustomerIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "Private Account",
                AccountType.CURRENT,
                null,
                ledgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer cannot be null");
    }

    @Test
    void nullLedgerAccountIsRejected() {
        assertThatThrownBy(() -> Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "Private Account",
                AccountType.CURRENT,
                customer,
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Ledger account cannot be null");
    }

    @Test
    void assetLedgerAccountIsRejected() {
        LedgerAccount assetLedgerAccount = LedgerAccount.createNew(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        assertThatThrownBy(() -> Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "Private Account",
                AccountType.CURRENT,
                customer,
                assetLedgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Customer account must use a liability ledger account"
                );
    }

    @Test
    void closedLedgerAccountIsRejected() {
        ledgerAccount.close();

        assertThatThrownBy(() -> Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Customer account cannot use a closed ledger account"
                );
    }

    @Test
    void nameIsTrimmed() {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "   Private Account   ",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        assertThat(account.getName())
                .isEqualTo("Private Account");
    }

    @Test
    void nullNameBecomesNull() {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                null,
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        assertThat(account.getName())
                .isNull();
    }

    @Test
    void blankNameBecomesNull() {
        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "     ",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        assertThat(account.getName())
                .isNull();
    }

    @Test
    void nameWith80CharactersIsAccepted() {
        String name = "a".repeat(80);

        Account account = Account.createNew(
                VALID_ACCOUNT_NUMBER,
                name,
                AccountType.CURRENT,
                customer,
                ledgerAccount
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
                name,
                AccountType.CURRENT,
                customer,
                ledgerAccount
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "Account name cannot exceed 80 characters"
                );
    }

    private Account createAccount() {
        return Account.createNew(
                VALID_ACCOUNT_NUMBER,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );
    }
}