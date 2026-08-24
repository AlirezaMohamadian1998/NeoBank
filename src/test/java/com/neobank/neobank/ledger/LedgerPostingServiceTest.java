package com.neobank.neobank.ledger;

import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class LedgerPostingServiceTest {

    private LedgerPostingService postingService;
    private BankTransaction transaction;
    private LedgerAccount liabilityAccount;

    @BeforeEach
    void setUp() {
        ReferenceGenerator referenceGenerator = mock(ReferenceGenerator.class);

        given(referenceGenerator.generate()).willReturn(
                "11111111111111111111111111111111",
                "22222222222222222222222222222222",
                "33333333333333333333333333333333"
        );

        postingService = new LedgerPostingService(referenceGenerator);

        transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                "0123456789abcdef0123456789abcdef",
                null
        );

        liabilityAccount = LedgerAccount.createNew(
                "abcdef0123456789abcdef0123456789",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
    }

    @Test
    void creditToLiabilityAccountIncreasesBalance() {
        LedgerEntry entry = postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        );

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(entry.getReference())
                .isEqualTo("11111111111111111111111111111111");
    }

    @Test
    void debitFromLiabilityAccountDecreasesBalance() {
        postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        );

        LedgerEntry entry = postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.DEBIT,
                new BigDecimal("40.00")
        );

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("60.00"));

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void debitToAssetAccountIncreasesBalance() {
        LedgerAccount assetAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        LedgerEntry entry = postingService.post(
                transaction,
                assetAccount,
                EntryDirection.DEBIT,
                new BigDecimal("100.00")
        );

        assertThat(assetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void creditFromAssetAccountDecreasesBalance() {
        LedgerAccount assetAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        postingService.post(
                transaction,
                assetAccount,
                EntryDirection.DEBIT,
                new BigDecimal("100.00")
        );

        LedgerEntry entry = postingService.post(
                transaction,
                assetAccount,
                EntryDirection.CREDIT,
                new BigDecimal("40.00")
        );

        assertThat(assetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("60.00"));

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    void postingCreatesExactlyOneEntryOnTransaction() {
        LedgerEntry result = postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        );

        assertThat(transaction.getEntries())
                .hasSize(1)
                .containsExactly(result);
    }

    @Test
    void createdEntryContainsCorrectPostingData() {
        BigDecimal amount = new BigDecimal("125.50");

        LedgerEntry entry = postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                amount
        );

        assertThat(entry.getAmount())
                .isEqualByComparingTo(new BigDecimal("125.50"));

        assertThat(entry.getDirection())
                .isEqualTo(EntryDirection.CREDIT);

        assertThat(entry.getLedgerAccount())
                .isSameAs(liabilityAccount);

        assertThat(entry.getBankTransaction())
                .isSameAs(transaction);

        assertThat(entry.getCurrency())
                .isEqualTo(CurrencyCode.TRY);

        assertThat(entry.getCurrency())
                .isEqualTo(liabilityAccount.getCurrency());

        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("125.50"));
    }

    @Test
    void postingLeavesTransactionPending() {
        postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        );

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void insufficientFundsLeaveBalanceAndEntriesUnchanged() {
        postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        );

        int entriesBefore = transaction.getEntries().size();

        assertThatThrownBy(() -> postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.DEBIT,
                new BigDecimal("100.01")
        ))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(transaction.getEntries())
                .hasSize(entriesBefore);
    }

    @Test
    void invalidAmountLeavesBalanceAndEntriesUnchanged() {
        BigDecimal balanceBefore = liabilityAccount.getBalance();

        assertThatThrownBy(() -> postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                BigDecimal.ZERO
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount must be greater than zero");

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(balanceBefore);

        assertThat(transaction.getEntries())
                .isEmpty();
    }

    @Test
    void nullTransactionIsRejected() {
        assertThatThrownBy(() -> postingService.post(
                null,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("transaction must not be null");

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void nullLedgerAccountIsRejected() {
        assertThatThrownBy(() -> postingService.post(
                transaction,
                null,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ledgerAccount must not be null");

        assertThat(transaction.getEntries())
                .isEmpty();
    }

    @Test
    void nullDirectionIsRejected() {
        assertThatThrownBy(() -> postingService.post(
                transaction,
                liabilityAccount,
                null,
                new BigDecimal("100.00")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("direction must not be null");

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        assertThat(transaction.getEntries())
                .isEmpty();
    }

    @Test
    void postingToCompletedTransactionIsRejectedBeforeChangingBalance() {
        LedgerAccount assetAccount = LedgerAccount.createNew(
                "44444444444444444444444444444444",
                LedgerAccountType.ASSET,
                CurrencyCode.TRY
        );

        postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        );

        postingService.post(
                transaction,
                assetAccount,
                EntryDirection.DEBIT,
                new BigDecimal("100.00")
        );

        transaction.complete();

        BigDecimal balanceBefore = liabilityAccount.getBalance();
        int entriesBefore = transaction.getEntries().size();

        assertThatThrownBy(() -> postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("50.00")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("transaction must be pending");

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(balanceBefore);

        assertThat(transaction.getEntries())
                .hasSize(entriesBefore);

        assertThat(transaction.getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void multiplePostingsCreateEntriesWithSuccessiveBalanceAfterValues() {
        LedgerEntry firstEntry = postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("100.00")
        );

        LedgerEntry secondEntry = postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.DEBIT,
                new BigDecimal("30.00")
        );

        LedgerEntry thirdEntry = postingService.post(
                transaction,
                liabilityAccount,
                EntryDirection.CREDIT,
                new BigDecimal("20.00")
        );

        assertThat(transaction.getEntries())
                .containsExactly(
                        firstEntry,
                        secondEntry,
                        thirdEntry
                );

        assertThat(firstEntry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("100.00"));

        assertThat(secondEntry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("70.00"));

        assertThat(thirdEntry.getBalanceAfter())
                .isEqualByComparingTo(new BigDecimal("90.00"));

        assertThat(liabilityAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("90.00"));
    }
}
