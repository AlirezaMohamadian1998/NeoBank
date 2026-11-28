package com.neobank.neobank.card.credit;

import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditCardTest {

    private final Customer customer = Customer.createNew("customer@example.com", "{bcrypt}password-hash", "Ada Lovelace");

    @Test
    void validCreationPreservesFieldsAndCreditAccount() {
        CreditAccount creditAccount = createCreditAccount();
        YearMonth expirationYearMonth = YearMonth.of(2029, 9);

        CreditCard creditCard = CreditCard.createNew(
                "0123456789abcdef0123456789abcdef",
                "5678",
                expirationYearMonth,
                YearMonth.of(2026, 9),
                creditAccount,
                TestCardLimits.generous()
        );

        assertThat(creditCard.getCardReference()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(creditCard.getLastFourDigits()).isEqualTo("5678");
        assertThat(creditCard.getExpirationYearMonth()).isEqualTo(expirationYearMonth);
        assertThat(creditCard.getStatus()).isEqualTo(CardStatus.INACTIVE);
        assertThat(creditCard.getCreditAccount()).isSameAs(creditAccount);
    }

    @Test
    void linkedAccountAndOwnerComeFromTheCreditAccount() {
        CreditAccount creditAccount = createCreditAccount();

        CreditCard creditCard = createCreditCard(creditAccount);

        assertThat(creditCard.getOwner()).isSameAs(customer);
        assertThat(creditCard.getLinkedLedgerAccount()).isSameAs(creditAccount.getLedgerAccount());
        assertThat(creditCard.getLinkedAccountNumber()).isEqualTo("55".repeat(7));
    }

    @Test
    void nullCreditAccountIsRejected() {
        assertThatThrownBy(() -> createCreditCard(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Credit account cannot be null");
    }

    @Test
    void creditAccountWithClosedLedgerIsRejected() {
        CreditAccount creditAccount = createCreditAccount();
        creditAccount.getLedgerAccount().close();

        assertThatThrownBy(() -> createCreditCard(creditAccount))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Credit account must be active.");
    }

    private CreditCard createCreditCard(CreditAccount creditAccount) {
        return CreditCard.createNew(
                "0123456789abcdef0123456789abcdef",
                "5678",
                YearMonth.of(2029, 9),
                YearMonth.of(2026, 9),
                creditAccount,
                TestCardLimits.generous()
        );
    }

    private CreditAccount createCreditAccount() {
        return CreditAccount.createNew("55".repeat(7), "5".repeat(32), customer, CurrencyCode.TRY, new BigDecimal("1000.00"), new BigDecimal("0.2499"), LocalDate.of(2026, 1, 15));
    }
}
