package com.neobank.neobank.creditaccount;

import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.MutableClock;
import com.neobank.neobank.shared.MutableClockConfiguration;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionIntegrationTestSupport;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Import({MySqlTestContainerConfiguration.class, MutableClockConfiguration.class})
class CreditStatementIntegrationTest extends TransactionIntegrationTestSupport {

    @Autowired
    private CreditStatementService creditStatementService;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private ReferenceGenerator referenceGenerator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MutableClock mutableClock;

    private CreditAccount creditAccount;

    private InternalAccount interestIncomeAccount;

    @BeforeEach
    void setUpCreditAccount() {
        interestIncomeAccount = internalAccountRepository.save(InternalAccount.createNew(
                InternalAccountPurpose.INTEREST_INCOME,
                LedgerAccount.createNew("9d3c8a21d9e64b5fa2c17e9084bd6d34", LedgerAccountType.INCOME, CurrencyCode.USD)
        ));

        mutableClock.setInstant(Instant.parse("2026-01-15T10:00:00Z"));

        creditAccount = creditAccountRepository.save(CreditAccount.createNew(
                "55".repeat(7),
                "5".repeat(32),
                customer,
                CurrencyCode.USD,
                new BigDecimal("5000.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 1, 15)
        ));
    }

    @AfterEach
    void resetClock() {
        mutableClock.reset();
    }

    @Test
    void firstCycleCreatesAStatementWithoutInterest() {
        purchase("1500.00", "2026-01-20T10:00:00Z");
        repay("500.00", "2026-01-20T18:00:00Z");

        close("2026-02-15T00:15:00Z");

        assertThat(creditStatementRepository.count()).isEqualTo(1);
        CreditStatement statement = latestStatement();

        assertThat(statement.getPeriodStart()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(statement.getStatementDate()).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(statement.getDueDate()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(statement.getOpeningBalance()).isEqualByComparingTo("0.00");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("1000.00");
        assertThat(statement.getAverageDailyBalance()).isEqualByComparingTo("838.71");
        assertThat(statement.getInterestCharged()).isEqualByComparingTo("0.00");
        assertThat(statement.getInterestTransaction()).isNull();
        assertThat(statement.getMinimumPayment()).isEqualByComparingTo("30.00");
        assertThat(reloadedCreditAccount().getNextStatementDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void statementPaidInFullByItsDueDateGivesNoInterest() {
        purchase("600.00", "2026-01-20T10:00:00Z");
        close("2026-02-15T00:15:00Z");

        repay("650.00", "2026-03-01T10:00:00Z");
        close("2026-03-15T00:15:00Z");

        CreditStatement statement = latestStatement();

        assertThat(statement.getOpeningBalance()).isEqualByComparingTo("600.00");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("-50.00");
        assertThat(statement.getAverageDailyBalance()).isEqualByComparingTo("300.00");
        assertThat(statement.getInterestCharged()).isEqualByComparingTo("0.00");
        assertThat(statement.getMinimumPayment()).isEqualByComparingTo("0.00");
    }

    @Test
    void partlyPaidStatementChargesInterestOnTheWholeNextCycle() {
        purchase("600.00", "2026-01-20T10:00:00Z");
        close("2026-02-15T00:15:00Z");

        repay("100.00", "2026-02-25T10:00:00Z");
        close("2026-03-15T00:15:00Z");

        CreditStatement statement = latestStatement();

        assertThat(statement.getAverageDailyBalance()).isEqualByComparingTo("535.71");
        assertThat(statement.getInterestCharged()).isEqualByComparingTo("10.27");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("510.27");
        assertThat(statement.getMinimumPayment()).isEqualByComparingTo("25.00");

        BankTransaction interestTransaction =
                bankTransactionRepository.findById(statement.getInterestTransaction().getId()).orElseThrow();

        assertThat(interestTransaction.getTransactionType()).isEqualTo(TransactionType.CREDIT_CARD_INTEREST);
        assertThat(interestTransaction.getRequestedAmount()).isEqualByComparingTo("10.27");
        assertThat(ledgerBalance(interestIncomeAccount.getLedgerAccount())).isEqualByComparingTo("10.27");
        assertThat(ledgerBalance(creditAccount.getLedgerAccount())).isEqualByComparingTo("510.27");
    }

    @Test
    void fullPaymentAfterTheDueDateStillChargesInterest() {
        purchase("600.00", "2026-01-20T10:00:00Z");
        close("2026-02-15T00:15:00Z");

        repay("600.00", "2026-03-13T10:00:00Z");
        close("2026-03-15T00:15:00Z");

        CreditStatement statement = latestStatement();

        assertThat(statement.getInterestCharged()).isEqualByComparingTo("10.68");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("10.68");
        assertThat(statement.getMinimumPayment()).isEqualByComparingTo("10.68");
    }

    @Test
    void gracePeriodComesBackOneCycleAfterAFullPayment() {
        purchase("600.00", "2026-01-20T10:00:00Z");
        close("2026-02-15T00:15:00Z");

        close("2026-03-15T00:15:00Z");
        assertThat(latestStatement().getInterestCharged()).isEqualByComparingTo("11.50");
        assertThat(latestStatement().getClosingBalance()).isEqualByComparingTo("611.50");

        repay("611.50", "2026-03-30T10:00:00Z");
        close("2026-04-15T00:15:00Z");
        assertThat(latestStatement().getInterestCharged()).isEqualByComparingTo("6.28");
        assertThat(latestStatement().getClosingBalance()).isEqualByComparingTo("6.28");

        repay("6.28", "2026-04-20T10:00:00Z");
        purchase("100.00", "2026-04-25T10:00:00Z");
        close("2026-05-15T00:15:00Z");
        assertThat(latestStatement().getInterestCharged()).isEqualByComparingTo("0.00");
        assertThat(latestStatement().getClosingBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void missedCyclesAreClosedInOrder() {
        purchase("600.00", "2026-01-20T10:00:00Z");

        close("2026-04-15T00:15:00Z");

        List<CreditStatement> statements = creditStatementRepository
                .findByCreditAccountOrderByStatementDateDesc(creditAccount, PageRequest.of(0, 10))
                .getContent();

        assertThat(statements).extracting(CreditStatement::getStatementDate).containsExactly(
                LocalDate.of(2026, 4, 15),
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 2, 15)
        );
        assertThat(statements).extracting(CreditStatement::getInterestCharged)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("12.98"), new BigDecimal("11.50"), new BigDecimal("0.00"));
        assertThat(statements.getFirst().getClosingBalance()).isEqualByComparingTo("624.48");
        assertThat(ledgerBalance(creditAccount.getLedgerAccount())).isEqualByComparingTo("624.48");
        assertThat(reloadedCreditAccount().getNextStatementDate()).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    void closingTwiceCreatesOneStatement() {
        purchase("600.00", "2026-01-20T10:00:00Z");

        close("2026-02-15T00:15:00Z");
        close("2026-02-15T00:15:00Z");

        assertThat(creditStatementRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentClosingsCreateOneStatement() throws Exception {
        purchase("600.00", "2026-01-20T10:00:00Z");
        mutableClock.setInstant(Instant.parse("2026-02-15T00:15:00Z"));

        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> closing = () -> {
            start.await();
            creditStatementService.generateStatementsUpTo(creditAccount.getId(), LocalDate.of(2026, 2, 15));
            return null;
        };

        try(ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Void> first = executor.submit(closing);
            Future<Void> second = executor.submit(closing);
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(creditStatementRepository.count()).isEqualTo(1);
    }

    private void purchase(String amount, String at) {
        post(TransactionType.CARD_PURCHASE, EntryDirection.DEBIT, EntryDirection.CREDIT, amount, at);
    }

    private void repay(String amount, String at) {
        post(TransactionType.CREDIT_CARD_REPAYMENT, EntryDirection.CREDIT, EntryDirection.DEBIT, amount, at);
    }

    private void post(TransactionType type, EntryDirection cardDirection, EntryDirection settlementDirection, String amount, String at) {
        mutableClock.setInstant(Instant.parse(at));

        transactionTemplate.executeWithoutResult(status -> {
            LedgerAccount cardLedger = ledgerAccountRepository.findById(creditAccount.getLedgerAccount().getId()).orElseThrow();
            LedgerAccount settlementLedger = ledgerAccountRepository.findById(internalAccountUSD.getLedgerAccount().getId()).orElseThrow();

            BankTransaction transaction = BankTransaction.createNew(
                    new BigDecimal(amount),
                    CurrencyCode.USD,
                    type,
                    referenceGenerator.generate(),
                    null
            );

            ledgerPostingService.post(transaction, cardLedger, cardDirection, new BigDecimal(amount));
            ledgerPostingService.post(transaction, settlementLedger, settlementDirection, new BigDecimal(amount));
            transaction.complete();
            bankTransactionRepository.save(transaction);
        });
    }

    private void close(String at) {
        Instant instant = Instant.parse(at);
        mutableClock.setInstant(instant);
        creditStatementService.generateStatementsUpTo(creditAccount.getId(), LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    private CreditStatement latestStatement() {
        return creditStatementRepository.findTop2ByCreditAccountOrderByStatementDateDesc(creditAccount).getFirst();
    }

    private CreditAccount reloadedCreditAccount() {
        return creditAccountRepository.findById(creditAccount.getId()).orElseThrow();
    }

    private BigDecimal ledgerBalance(LedgerAccount ledgerAccount) {
        return ledgerAccountRepository.findById(ledgerAccount.getId()).orElseThrow().getBalance();
    }
}
