package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.TransactionIntegrationTestSupport;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class CreditStatementPersistenceIntegrationTest extends TransactionIntegrationTestSupport {

    @Test
    void creditAccountPersistsInterestRateAndNextStatementDate() {
        CreditAccount saved = creditAccountRepository.save(creditAccount("55".repeat(7), "5".repeat(32)));

        CreditAccount reloaded = creditAccountRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getAnnualInterestRate()).isEqualTo(new BigDecimal("0.2499"));
        assertThat(reloaded.getNextStatementDate()).isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void statementWithInterestTransactionIsPersisted() {
        CreditAccount creditAccount = creditAccountRepository.save(creditAccount("55".repeat(7), "5".repeat(32)));
        BankTransaction interestTransaction = bankTransactionRepository.save(BankTransaction.createNew(
                new BigDecimal("12.30"),
                CurrencyCode.TRY,
                TransactionType.CREDIT_CARD_INTEREST,
                "b".repeat(32),
                null
        ));

        CreditStatement saved = creditStatementRepository.save(CreditStatement.createNew(
                "a".repeat(32),
                creditAccount,
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 12),
                new BigDecimal("100.00"),
                new BigDecimal("612.30"),
                new BigDecimal("450.00"),
                new BigDecimal("12.30"),
                new BigDecimal("25.00"),
                interestTransaction
        ));

        CreditStatement reloaded = creditStatementRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatementReference()).isEqualTo("a".repeat(32));
        assertThat(reloaded.getCreditAccount().getId()).isEqualTo(creditAccount.getId());
        assertThat(reloaded.getPeriodStart()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(reloaded.getStatementDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(reloaded.getClosingBalance()).isEqualTo(new BigDecimal("612.30"));
        assertThat(reloaded.getInterestCharged()).isEqualTo(new BigDecimal("12.30"));
        assertThat(reloaded.getInterestTransaction().getId()).isEqualTo(interestTransaction.getId());
    }

    @Test
    void secondStatementForTheSameDateIsRejected() {
        CreditAccount creditAccount = creditAccountRepository.save(creditAccount("55".repeat(7), "5".repeat(32)));
        creditStatementRepository.save(statement("a".repeat(32), creditAccount, LocalDate.of(2026, 2, 15)));

        assertThatThrownBy(() -> creditStatementRepository.save(statement("c".repeat(32), creditAccount, LocalDate.of(2026, 2, 15))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statementsAreFoundNewestFirstAndOnlyForTheirAccount() {
        CreditAccount creditAccount = creditAccountRepository.save(creditAccount("55".repeat(7), "5".repeat(32)));
        CreditAccount otherCreditAccount = creditAccountRepository.save(creditAccount("66".repeat(7), "6".repeat(32)));

        creditStatementRepository.save(statement("e".repeat(32), creditAccount, LocalDate.of(2026, 2, 15)));
        creditStatementRepository.save(statement("a".repeat(32), creditAccount, LocalDate.of(2026, 3, 15)));
        creditStatementRepository.save(statement("c".repeat(32), creditAccount, LocalDate.of(2026, 4, 15)));
        creditStatementRepository.save(statement("d".repeat(32), otherCreditAccount, LocalDate.of(2026, 5, 15)));

        assertThat(creditStatementRepository.findTop2ByCreditAccountOrderByStatementDateDesc(creditAccount))
                .extracting(CreditStatement::getStatementReference)
                .containsExactly("c".repeat(32), "a".repeat(32));

        List<String> references = creditStatementRepository
                .findByCreditAccountOrderByStatementDateDesc(creditAccount, PageRequest.of(0, 10))
                .map(CreditStatement::getStatementReference)
                .getContent();

        assertThat(references).containsExactly("c".repeat(32), "a".repeat(32), "e".repeat(32));

        assertThat(creditStatementRepository.findByStatementReferenceAndCreditAccount("d".repeat(32), creditAccount))
                .isEmpty();
    }

    private CreditAccount creditAccount(String accountNumber, String ledgerReference) {
        return CreditAccount.createNew(
                accountNumber,
                ledgerReference,
                customer,
                CurrencyCode.TRY,
                new BigDecimal("1000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        );
    }

    private static CreditStatement statement(String reference, CreditAccount creditAccount, LocalDate statementDate) {
        return CreditStatement.createNew(
                reference,
                creditAccount,
                statementDate.minusMonths(1),
                statementDate,
                statementDate.plusDays(25),
                new BigDecimal("0.00"),
                new BigDecimal("100.00"),
                new BigDecimal("50.00"),
                new BigDecimal("0.00"),
                new BigDecimal("25.00"),
                null
        );
    }
}
