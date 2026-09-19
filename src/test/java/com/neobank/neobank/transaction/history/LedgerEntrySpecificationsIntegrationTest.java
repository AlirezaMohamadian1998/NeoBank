package com.neobank.neobank.transaction.history;

import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.config.JpaAuditingConfig;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.tuple;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@Import({MySqlTestContainerConfiguration.class, JpaAuditingConfig.class})
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

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.TRANSFER,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry sourceEntry = transaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                sourceLedgerAccount
        );

        LedgerEntry targetEntry = transaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1500.00"),
                EntryDirection.CREDIT,
                targetLedgerAccount
        );

        transaction.complete();

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

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.TRANSFER,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry sourceEntry = transaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                sourceLedgerAccount
        );

        LedgerEntry targetEntry = transaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1500.00"),
                EntryDirection.CREDIT,
                targetLedgerAccount
        );

        transaction.complete();

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

        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1500.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntry = depositTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        BankTransaction withdrawalTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "6".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntry = withdrawalTransaction.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntry = withdrawalTransaction.addEntry(
                "8".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        depositTransaction.complete();
        withdrawalTransaction.complete();

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

        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1500.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntry = depositTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        BankTransaction withdrawalTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "6".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntry = withdrawalTransaction.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntry = withdrawalTransaction.addEntry(
                "8".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        depositTransaction.complete();
        withdrawalTransaction.complete();

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

        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1500.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntry = depositTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccountUsd
        );

        BankTransaction withdrawalTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.EUR,
                TransactionType.WITHDRAWAL,
                "6".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntry = withdrawalTransaction.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntry = withdrawalTransaction.addEntry(
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

        depositTransaction.complete();
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

        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("1500.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1500.00"),
                new BigDecimal("3000.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntry = depositTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1500.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        BankTransaction withdrawalTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "6".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntry = withdrawalTransaction.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("2000.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntry = withdrawalTransaction.addEntry(
                "8".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        BankTransaction withdrawalTransactionOverMax = BankTransaction.createNew(
                new BigDecimal("2000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "10".repeat(16),
                null
        );

        LedgerEntry customerWithdrawalEntryOverMax = withdrawalTransactionOverMax.addEntry(
                "11".repeat(16),
                new BigDecimal("2000.00"),
                new BigDecimal("0.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntryOverMax = withdrawalTransactionOverMax.addEntry(
                "12".repeat(16),
                new BigDecimal("2000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );
        BankTransaction depositTransactionBelowMin = BankTransaction.createNew(
                new BigDecimal("500.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "13".repeat(16),
                null
        );

        LedgerEntry customerDepositEntryBelowMin = depositTransactionBelowMin.addEntry(
                "14".repeat(16),
                new BigDecimal("500.00"),
                new BigDecimal("500.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntryBelowMin = depositTransactionBelowMin.addEntry(
                "15".repeat(16),
                new BigDecimal("500.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        depositTransaction.complete();
        withdrawalTransaction.complete();
        withdrawalTransactionOverMax.complete();
        depositTransactionBelowMin.complete();

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

        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("1500.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1500.00"),
                new BigDecimal("3000.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntry = depositTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1500.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        BankTransaction withdrawalTransaction1 = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "6".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntry1 = withdrawalTransaction1.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("2000.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntry1 = withdrawalTransaction1.addEntry(
                "8".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        BankTransaction withdrawalTransaction2 = BankTransaction.createNew(
                new BigDecimal("2000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "10".repeat(16),
                null
        );

        LedgerEntry customerWithdrawalEntry2 = withdrawalTransaction2.addEntry(
                "11".repeat(16),
                new BigDecimal("2000.00"),
                new BigDecimal("0.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntry2 = withdrawalTransaction2.addEntry(
                "12".repeat(16),
                new BigDecimal("2000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        depositTransaction.complete();
        withdrawalTransaction1.complete();
        withdrawalTransaction2.complete();

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
@Import({MySqlTestContainerConfiguration.class, JpaAuditingConfig.class})
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

        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "33333333333333333333333333333333",
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1500.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntry = depositTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        depositTransaction.complete();
        transactionRepository.saveAndFlush(depositTransaction);


        BankTransaction withdrawalTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "6".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntry = withdrawalTransaction.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntry = withdrawalTransaction.addEntry(
                "8".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        given(auditingDateTimeProvider.getNow())
                .willReturn(Optional.of(Instant.parse("2026-09-18T12:00:00Z")));

        withdrawalTransaction.complete();
        transactionRepository.saveAndFlush(withdrawalTransaction);

        BankTransaction depositTransactionBeforeStart = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "a".repeat(32),
                null
        );

        LedgerEntry customerDepositEntryBeforeStart = depositTransactionBeforeStart.addEntry(
                "b".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1500.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        LedgerEntry internalDepositEntryBeforeStart = depositTransactionBeforeStart.addEntry(
                "c".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.DEBIT,
                internalLedgerAccount
        );

        given(auditingDateTimeProvider.getNow())
                .willReturn(Optional.of(Instant.parse("2026-08-31T12:00:00Z")));

        depositTransactionBeforeStart.complete();
        transactionRepository.saveAndFlush(depositTransactionBeforeStart);

        BankTransaction withdrawalTransactionAtEnd = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "d".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntryAtEnd = withdrawalTransactionAtEnd.addEntry(
                "e".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("500.00"),
                EntryDirection.DEBIT,
                ledgerAccount
        );

        LedgerEntry internalWithdrawalEntryAtEnd = withdrawalTransactionAtEnd.addEntry(
                "f".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("100000.00"),
                EntryDirection.CREDIT,
                internalLedgerAccount
        );

        given(auditingDateTimeProvider.getNow())
                .willReturn(Optional.of(Instant.parse("2026-09-10T12:00:00Z")));

        withdrawalTransactionAtEnd.complete();
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
