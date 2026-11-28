package com.neobank.neobank.transaction.history;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.config.JpaAuditingConfig;
import com.neobank.neobank.shared.config.TimeConfig;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.neobank.neobank.transaction.history.LedgerEntryFixtures.completedTransaction;
import static com.neobank.neobank.transaction.history.LedgerEntryFixtures.entryOn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.tuple;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@Import({MySqlTestContainerConfiguration.class, JpaAuditingConfig.class, TimeConfig.class})
class LedgerEntrySpecificationsIntegrationTest {

    @Autowired
    private BankTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Test
    void restrictsResultsToSpecifiedLedgerAccounts() {
        LedgerAccount sourceLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount targetLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        BankTransaction transaction = completedTransaction(
                TransactionType.TRANSFER,
                CurrencyCode.USD,
                "1000.00",
                "33333333333333333333333333333333",
                "4".repeat(32), sourceLedgerAccount,
                "5".repeat(32), targetLedgerAccount
        );

        LedgerEntry sourceEntry = entryOn(transaction, sourceLedgerAccount);

        BankTransaction savedTransaction = transactionRepository.save(transaction);

        Specification<LedgerEntry> spec = LedgerEntrySpecifications.belongsToAccount(List.of(sourceLedgerAccount.getId()));

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .hasSize(1);

        LedgerEntry queriedEntry = result.getFirst();

        assertThat(queriedEntry.getReference())
                .isEqualTo(sourceEntry.getReference());

        assertThat(queriedEntry.getLedgerAccount().getId())
                .isEqualTo(sourceLedgerAccount.getId());

        assertThat(queriedEntry.getBankTransaction().getId())
                .isEqualTo(savedTransaction.getId());
    }

    @Test
    void emptyLedgerAccountIdsReturnNoEntries() {
        LedgerAccount sourceLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount targetLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        BankTransaction transaction = completedTransaction(
                TransactionType.TRANSFER,
                CurrencyCode.USD,
                "1000.00",
                "33333333333333333333333333333333",
                "4".repeat(32), sourceLedgerAccount,
                "5".repeat(32), targetLedgerAccount
        );

        transactionRepository.save(transaction);

        Specification<LedgerEntry> spec = LedgerEntrySpecifications.belongsToAccount(List.of());

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .isEmpty();
    }

    @Test
    void absentOptionalFiltersDoNotRestrictAccountHistory() {
        LedgerAccount ledgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount internalLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.ASSET,
                        CurrencyCode.USD
                )
        );

        BankTransaction depositTransaction = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "1000.00",
                "33333333333333333333333333333333",
                "5".repeat(32), internalLedgerAccount,
                "4".repeat(32), ledgerAccount
        );

        LedgerEntry customerDepositEntry = entryOn(depositTransaction, ledgerAccount);

        BankTransaction withdrawalTransaction = completedTransaction(
                TransactionType.WITHDRAWAL,
                CurrencyCode.USD,
                "1000.00",
                "6".repeat(32),
                "7".repeat(32), ledgerAccount,
                "8".repeat(32), internalLedgerAccount
        );

        LedgerEntry customerWithdrawalEntry = entryOn(withdrawalTransaction, ledgerAccount);

        transactionRepository.saveAll(List.of(depositTransaction, withdrawalTransaction));

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(List.of(ledgerAccount.getId()))
                        .and(LedgerEntrySpecifications.hasTransactionType(null))
                        .and(LedgerEntrySpecifications.hasRequestedCurrency(null))
                        .and(LedgerEntrySpecifications.createdAfterOrEqual(null))
                        .and(LedgerEntrySpecifications.createdBefore(null))
                        .and(LedgerEntrySpecifications.amountIsGreaterThanOrEqual(null))
                        .and(LedgerEntrySpecifications.amountIsLessThanOrEqual(null));

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .hasSize(2);

        assertThat(result)
                .extracting(
                        LedgerEntry::getReference,
                        LedgerEntry::getId,
                        entry -> entry.getBankTransaction().getId()
                ).containsExactlyInAnyOrder(
                        tuple("4".repeat(32), customerDepositEntry.getId(), customerDepositEntry.getBankTransaction().getId()),
                        tuple("7".repeat(32), customerWithdrawalEntry.getId(), customerWithdrawalEntry.getBankTransaction().getId())
                );
    }

    @Test
    void filtersByTransactionType() {
        LedgerAccount ledgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount internalLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.ASSET,
                        CurrencyCode.USD
                )
        );

        BankTransaction depositTransaction = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "1000.00",
                "33333333333333333333333333333333",
                "5".repeat(32), internalLedgerAccount,
                "4".repeat(32), ledgerAccount
        );

        LedgerEntry customerDepositEntry = entryOn(depositTransaction, ledgerAccount);

        BankTransaction withdrawalTransaction = completedTransaction(
                TransactionType.WITHDRAWAL,
                CurrencyCode.USD,
                "1000.00",
                "6".repeat(32),
                "7".repeat(32), ledgerAccount,
                "8".repeat(32), internalLedgerAccount
        );

        transactionRepository.saveAll(List.of(depositTransaction, withdrawalTransaction));

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(List.of(ledgerAccount.getId()))
                .and(LedgerEntrySpecifications.hasTransactionType(TransactionType.DEPOSIT));

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .hasSize(1);

        assertThat(result)
                .extracting(
                        LedgerEntry::getReference,
                        LedgerEntry::getId,
                        entry -> entry.getBankTransaction().getId()
                ).containsExactly(
                        tuple("4".repeat(32), customerDepositEntry.getId(), customerDepositEntry.getBankTransaction().getId())
                );
    }

    @Test
    void filtersByRequestedCurrencyRatherThanEntryCurrency() {
        LedgerAccount ledgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount internalLedgerAccountUsd = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.ASSET,
                        CurrencyCode.USD
                )
        );

        LedgerAccount internalLedgerAccountEur = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "9".repeat(32),
                        LedgerAccountType.ASSET,
                        CurrencyCode.EUR
                )
        );

        BankTransaction depositTransaction = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "1000.00",
                "33333333333333333333333333333333",
                "5".repeat(32), internalLedgerAccountUsd,
                "4".repeat(32), ledgerAccount
        );

        LedgerEntry customerDepositEntry = entryOn(depositTransaction, ledgerAccount);

        BankTransaction withdrawalTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.EUR,
                TransactionType.WITHDRAWAL,
                "6".repeat(32),
                null
        );

        withdrawalTransaction.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        withdrawalTransaction.addEntry(
                "8".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccountEur
        );

        FxInfo fxInfo = FxInfo.createNew(
                UUID.randomUUID().toString(),
                Set.of(
                        FxRate.createNew(
                                CurrencyContext.REQUEST,
                                CurrencyCode.EUR,
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.DESTINATION,
                                CurrencyCode.EUR,
                                BigDecimal.ONE
                        ),
                        FxRate.createNew(
                                CurrencyContext.SOURCE,
                                CurrencyCode.USD,
                                BigDecimal.ONE
                        )
                )
        );

        withdrawalTransaction.addFxInfo(fxInfo);
        withdrawalTransaction.complete();

        transactionRepository.saveAll(List.of(depositTransaction, withdrawalTransaction));

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(List.of(ledgerAccount.getId()))
                        .and(LedgerEntrySpecifications.hasRequestedCurrency(CurrencyCode.USD));

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .hasSize(1);

        assertThat(result)
                .extracting(
                        LedgerEntry::getReference,
                        LedgerEntry::getId,
                        entry -> entry.getBankTransaction().getId()
                ).containsExactly(
                        tuple("4".repeat(32), customerDepositEntry.getId(), customerDepositEntry.getBankTransaction().getId())
                );
    }

    @Test
    void filtersByInclusiveAppliedAmountBounds() {
        LedgerAccount ledgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount internalLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.ASSET,
                        CurrencyCode.USD
                )
        );

        BankTransaction depositTransaction = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "1500.00",
                "33333333333333333333333333333333",
                "5".repeat(32), internalLedgerAccount,
                "4".repeat(32), ledgerAccount
        );

        LedgerEntry customerDepositEntry = entryOn(depositTransaction, ledgerAccount);

        BankTransaction withdrawalTransaction = completedTransaction(
                TransactionType.WITHDRAWAL,
                CurrencyCode.USD,
                "1000.00",
                "6".repeat(32),
                "7".repeat(32), ledgerAccount,
                "8".repeat(32), internalLedgerAccount
        );

        LedgerEntry customerWithdrawalEntry = entryOn(withdrawalTransaction, ledgerAccount);

        BankTransaction withdrawalTransactionOverMax = completedTransaction(
                TransactionType.WITHDRAWAL,
                CurrencyCode.USD,
                "2000.00",
                "10".repeat(16),
                "11".repeat(16), ledgerAccount,
                "12".repeat(16), internalLedgerAccount
        );
        BankTransaction depositTransactionBelowMin = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "500.00",
                "13".repeat(16),
                "15".repeat(16), internalLedgerAccount,
                "14".repeat(16), ledgerAccount
        );

        transactionRepository.saveAll(List.of(withdrawalTransaction, depositTransaction, withdrawalTransactionOverMax, depositTransactionBelowMin));

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(List.of(ledgerAccount.getId()))
                        .and(LedgerEntrySpecifications.amountIsGreaterThanOrEqual(new BigDecimal("1000.00")))
                        .and(LedgerEntrySpecifications.amountIsLessThanOrEqual(new BigDecimal("1500.00")));

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .hasSize(2);

        assertThat(result)
                .extracting(
                        LedgerEntry::getReference,
                        LedgerEntry::getId,
                        entry -> entry.getBankTransaction().getId()
                ).containsExactlyInAnyOrder(
                        tuple("4".repeat(32), customerDepositEntry.getId(), customerDepositEntry.getBankTransaction().getId()),
                        tuple("7".repeat(32), customerWithdrawalEntry.getId(), customerWithdrawalEntry.getBankTransaction().getId())
                );
    }

    @Test
    void combinesFiltersUsingAnd() {
        LedgerAccount ledgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount internalLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.ASSET,
                        CurrencyCode.USD
                )
        );

        BankTransaction depositTransaction = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "1500.00",
                "33333333333333333333333333333333",
                "5".repeat(32), internalLedgerAccount,
                "4".repeat(32), ledgerAccount
        );

        BankTransaction withdrawalTransaction1 = completedTransaction(
                TransactionType.WITHDRAWAL,
                CurrencyCode.USD,
                "1000.00",
                "6".repeat(32),
                "7".repeat(32), ledgerAccount,
                "8".repeat(32), internalLedgerAccount
        );

        LedgerEntry customerWithdrawalEntry1 = entryOn(withdrawalTransaction1, ledgerAccount);

        BankTransaction withdrawalTransaction2 = completedTransaction(
                TransactionType.WITHDRAWAL,
                CurrencyCode.USD,
                "2000.00",
                "10".repeat(16),
                "11".repeat(16), ledgerAccount,
                "12".repeat(16), internalLedgerAccount
        );

        transactionRepository.saveAll(List.of(withdrawalTransaction1, depositTransaction, withdrawalTransaction2));

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(List.of(ledgerAccount.getId()))
                        .and(LedgerEntrySpecifications.hasTransactionType(TransactionType.WITHDRAWAL))
                        .and(LedgerEntrySpecifications.amountIsGreaterThanOrEqual(new BigDecimal("1000.00")))
                        .and(LedgerEntrySpecifications.amountIsLessThanOrEqual(new BigDecimal("1500.00")));

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .hasSize(1);

        assertThat(result)
                .extracting(
                        LedgerEntry::getReference,
                        LedgerEntry::getId,
                        entry -> entry.getBankTransaction().getId()
                ).containsExactlyInAnyOrder(
                        tuple("7".repeat(32), customerWithdrawalEntry1.getId(), customerWithdrawalEntry1.getBankTransaction().getId())
                );
    }
}

@DataJpaTest
@Import({MySqlTestContainerConfiguration.class, JpaAuditingConfig.class, TimeConfig.class})
class LedgerEntrySpecificationsDateIntegrationTest {

    @Autowired
    private BankTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @MockitoBean
    private DateTimeProvider auditingDateTimeProvider;

    @Test
    void filtersByInclusiveStartAndExclusiveEndDate() {
        given(auditingDateTimeProvider.getNow())
                .willReturn(Optional.of(Instant.parse("2026-09-01T12:00:00Z")));

        LedgerAccount ledgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "11111111111111111111111111111111",
                        LedgerAccountType.LIABILITY,
                        CurrencyCode.USD
                )
        );

        LedgerAccount internalLedgerAccount = ledgerAccountRepository.save(
                LedgerAccount.createNew(
                        "22222222222222222222222222222222",
                        LedgerAccountType.ASSET,
                        CurrencyCode.USD
                )
        );

        BankTransaction depositTransaction = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "1000.00",
                "33333333333333333333333333333333",
                "5".repeat(32), internalLedgerAccount,
                "4".repeat(32), ledgerAccount
        );

        LedgerEntry customerDepositEntry = entryOn(depositTransaction, ledgerAccount);
        transactionRepository.saveAndFlush(depositTransaction);


        BankTransaction withdrawalTransaction = completedTransaction(

                TransactionType.WITHDRAWAL,

                CurrencyCode.USD,

                "1000.00",

                "6".repeat(32),

                "7".repeat(32), ledgerAccount,

                "8".repeat(32), internalLedgerAccount

        );

        given(auditingDateTimeProvider.getNow())
                .willReturn(Optional.of(Instant.parse("2026-09-18T12:00:00Z")));
        transactionRepository.saveAndFlush(withdrawalTransaction);

        BankTransaction depositTransactionBeforeStart = completedTransaction(
                TransactionType.DEPOSIT,
                CurrencyCode.USD,
                "1000.00",
                "a".repeat(32),
                "c".repeat(32), internalLedgerAccount,
                "b".repeat(32), ledgerAccount
        );

        given(auditingDateTimeProvider.getNow())
                .willReturn(Optional.of(Instant.parse("2026-08-31T12:00:00Z")));
        transactionRepository.saveAndFlush(depositTransactionBeforeStart);

        BankTransaction withdrawalTransactionAtEnd = completedTransaction(
                TransactionType.WITHDRAWAL,
                CurrencyCode.USD,
                "1000.00",
                "d".repeat(32),
                "e".repeat(32), ledgerAccount,
                "f".repeat(32), internalLedgerAccount
        );

        given(auditingDateTimeProvider.getNow())
                .willReturn(Optional.of(Instant.parse("2026-09-10T12:00:00Z")));
        transactionRepository.saveAndFlush(withdrawalTransactionAtEnd);

        Specification<LedgerEntry> spec =
                LedgerEntrySpecifications.belongsToAccount(List.of(ledgerAccount.getId()))
                        .and(LedgerEntrySpecifications.createdAfterOrEqual(Instant.parse("2026-09-01T12:00:00Z")))
                        .and(LedgerEntrySpecifications.createdBefore(Instant.parse("2026-09-10T12:00:00Z")));

        List<LedgerEntry> result = ledgerEntryRepository.findAll(
                spec,
                PageRequest.of(
                        0,
                        10,
                        Sort.by(Sort.Order.desc("createdAt"))
                )
        ).getContent();

        assertThat(result)
                .hasSize(1);

        assertThat(result)
                .extracting(
                        LedgerEntry::getReference,
                        LedgerEntry::getId,
                        entry -> entry.getBankTransaction().getId()
                ).containsExactly(
                        tuple("4".repeat(32), customerDepositEntry.getId(), customerDepositEntry.getBankTransaction().getId())
                );
    }
}
