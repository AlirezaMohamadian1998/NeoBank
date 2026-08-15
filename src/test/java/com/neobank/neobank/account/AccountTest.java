package com.neobank.neobank.account;

import com.neobank.neobank.customer.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

         account = Account.createNew(
                "12345678900987",
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY,
                 customer
        );
    }

    @Test
    void creditUpdatesAndReturnsBalance() {
        BigDecimal result = account.credit(new BigDecimal("125.50"));
        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("125.50"));
        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("125.50"));
    }

    @Test
    void creditAccumulatesBalance() {
        account.credit(new BigDecimal("10.25"));
        BigDecimal result = account.credit(new BigDecimal("2.75"));
        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("13"));
        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("13"));
    }

    @Test
    void creditAcceptsInsignificantTrailingZeros() {
        account.credit(new BigDecimal("10.000"));
        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("10"));
        assertThat(account.getBalance().scale())
                .isEqualTo(2);
    }

    @Test
    void creditRejectsNullAmount() {
        assertThatThrownBy(() -> account.credit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not be null");
        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void creditRejectsZeroAmount() {
        assertThatThrownBy(() -> account.credit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");
        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void creditRejectsNegativeAmount() {
        assertThatThrownBy(() -> account.credit(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");
        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void creditRejectsAmountWithMoreThanTwoMeaningfulDecimalPlaces() {
        assertThatThrownBy(() -> account.credit(new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not have more than 2 decimal places");
        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void debitUpdatesAndReturnsBalance() {
        account.credit(new BigDecimal("100.00"));
        BigDecimal result = account.debit(new BigDecimal("50.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void debitCanReduceBalanceToZero() {
        account.credit(new BigDecimal("100.00"));
        BigDecimal result = account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(result)
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void debitRejectsNullAmount() {
        account.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.debit(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not be null");

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitRejectsZeroAmount() {
        account.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.debit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitRejectsNegativeAmount() {
        account.credit(new BigDecimal("100.00"));
        assertThatThrownBy(() -> account.debit(new BigDecimal("-10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitRejectsAmountWithMoreThanTwoMeaningfulDecimalPlaces() {
        account.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("10.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must not have more than 2 decimal places");

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitThrowsInsufficientFundsExceptionWhenBalanceIsTooLow() {
        account.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(account.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

    }
}
