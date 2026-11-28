package com.neobank.neobank.transaction;

import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class FxInfoPersistenceIntegrationTest extends TransactionIntegrationTestSupport {

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void transactionCanPersistFxSnapshotWithoutLockId() {
        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "1".repeat(32),
                "Automatic FX"
        );

        transaction.addFxInfo(FxInfo.createNew(
                null,
                Set.of(
                        FxRate.createNew(
                                CurrencyContext.REQUEST,
                                CurrencyCode.USD,
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.SOURCE,
                                CurrencyCode.USD,
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.DESTINATION,
                                CurrencyCode.TRY,
                                new BigDecimal("40.00")
                        )
                )
        ));

        transaction.addEntry(
                "2".repeat(32),
                new BigDecimal("100.00"),
                new BigDecimal("10100.00"),
                EntryDirection.DEBIT,
                internalAccountUSD.getLedgerAccount()
        );

        transaction.addEntry(
                "3".repeat(32),
                new BigDecimal("4000.00"),
                new BigDecimal("4000.00"),
                EntryDirection.CREDIT,
                account.getLedgerAccount()
        );

        transaction.complete();
        BankTransaction savedTransaction = bankTransactionRepository.saveAndFlush(transaction);

        assertThat(bankTransactionRepository.count())
                .isOne();

        transactionTemplate.executeWithoutResult(status -> {
            BankTransaction reloadedTransaction = bankTransactionRepository.findById(savedTransaction.getId())
                    .orElseThrow();

            FxInfo savedFxInfo = reloadedTransaction.getFxInfo();

            assertThat(reloadedTransaction.getStatus())
                    .isSameAs(TransactionStatus.COMPLETED);

            assertThat(savedFxInfo.getId())
                    .isNotNull();

            assertThat(savedFxInfo.getLockId())
                    .isNull();

            assertThat(savedFxInfo.getRates().size())
                    .isEqualTo(3);

            assertThat(savedFxInfo.getRate(CurrencyContext.REQUEST))
                    .isEqualByComparingTo(BigDecimal.ONE);

            assertThat(savedFxInfo.getRate(CurrencyContext.SOURCE))
                    .isEqualByComparingTo(BigDecimal.ONE);

            assertThat(savedFxInfo.getRate(CurrencyContext.DESTINATION))
                    .isEqualByComparingTo(new BigDecimal("40.00"));
        });
    }
}
