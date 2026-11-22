package com.neobank.neobank.transaction;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.config.JpaAuditingConfig;
import com.neobank.neobank.shared.config.TimeConfig;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({MySqlTestContainerConfiguration.class, JpaAuditingConfig.class, TimeConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BankTransactionLockingIntegrationTest {

    private static final String PURCHASE_REFERENCE = "a".repeat(32);

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private ExecutorService executor;

    private List<Long> ledgerAccountIds;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(2);

        LedgerAccount fundingAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew("b".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY)
        );

        LedgerAccount merchantAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew("c".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY)
        );

        ledgerAccountIds = List.of(fundingAccount.getId(), merchantAccount.getId());

        BankTransaction purchase = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.CARD_PURCHASE,
                PURCHASE_REFERENCE,
                null
        );

        purchase.addEntry("d".repeat(32), new BigDecimal("100.00"), new BigDecimal("0.00"), EntryDirection.DEBIT, fundingAccount);
        purchase.addEntry("e".repeat(32), new BigDecimal("100.00"), new BigDecimal("100.00"), EntryDirection.CREDIT, merchantAccount);
        purchase.complete();

        bankTransactionRepository.save(purchase);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        ledgerEntryRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        ledgerAccountRepository.deleteAllById(ledgerAccountIds);
    }

    @Test
    void findsTransactionOnlyWhenReferenceAndTypeMatch() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(bankTransactionRepository.findForUpdateByReferenceAndTransactionType(
                    PURCHASE_REFERENCE, TransactionType.CARD_PURCHASE
            )).isPresent();

            assertThat(bankTransactionRepository.findForUpdateByReferenceAndTransactionType(
                    PURCHASE_REFERENCE, TransactionType.TRANSFER
            )).isEmpty();

            assertThat(bankTransactionRepository.findForUpdateByReferenceAndTransactionType(
                    "f".repeat(32), TransactionType.CARD_PURCHASE
            )).isEmpty();
        });
    }

    @Test
    void secondLockerWaitsForFirstToCommitAndThenSeesItsChanges() throws Exception {
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            BankTransaction purchase = bankTransactionRepository
                    .findForUpdateByReferenceAndTransactionType(PURCHASE_REFERENCE, TransactionType.CARD_PURCHASE)
                    .orElseThrow();

            firstHoldsLock.countDown();
            await(releaseFirst);

            purchase.markRefunded();
        }));

        assertThat(firstHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

        Future<TransactionStatus> second = executor.submit(() -> transactionTemplate.execute(status ->
                bankTransactionRepository
                        .findForUpdateByReferenceAndTransactionType(PURCHASE_REFERENCE, TransactionType.CARD_PURCHASE)
                        .orElseThrow()
                        .getStatus()
        ));

        assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                .as("second caller must block while the first holds the row lock")
                .isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);

        assertThat(second.get(5, TimeUnit.SECONDS))
                .as("after waiting, the second caller reads the committed state, not the stale COMPLETED")
                .isEqualTo(TransactionStatus.REFUNDED);
    }

    private static void await(CountDownLatch latch) {
        try {
            if(!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
