package com.neobank.neobank.card;

import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreditCardPersistenceIntegrationTest extends DebitCardIntegrationTestSupport {

    @Test
    void creditCardIsLoadedAsCreditCardWithItsCreditAccount() {
        CreditAccount creditAccount = creditAccountRepository.save(createCreditAccount("55".repeat(7), "5".repeat(32)));

        cardRepository.save(CreditCard.createNew(
                "3".repeat(32),
                "5678",
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                creditAccount,
                TestCardLimits.generous()
        ));

        transactionTemplate.executeWithoutResult(status -> {
            Card card = cardRepository.findByCardReference("3".repeat(32)).orElseThrow();

            assertThat(card).isInstanceOf(CreditCard.class);
            assertThat(card.getOwner().getEmail()).isEqualTo(customer.getEmail());
            assertThat(card.getLinkedAccountNumber()).isEqualTo("55".repeat(7));
            assertThat(card.getLinkedLedgerAccount().isNegativeBalanceAllowed()).isTrue();
            assertThat(((CreditCard) card).getCreditAccount().getCreditLimit()).isEqualTo(new BigDecimal("1000.00"));
        });
    }

    @Test
    void duplicateCreditAccountNumberIsRejected() {
        creditAccountRepository.save(createCreditAccount("55".repeat(7), "5".repeat(32)));

        assertThatThrownBy(() -> creditAccountRepository.save(createCreditAccount("55".repeat(7), "6".repeat(32))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private CreditAccount createCreditAccount(String accountNumber, String ledgerReference) {
        return CreditAccount.createNew(accountNumber, ledgerReference, customer, CurrencyCode.TRY, new BigDecimal("1000.00"), new BigDecimal("0.2499"), LocalDate.of(2026, 1, 15));
    }
}
