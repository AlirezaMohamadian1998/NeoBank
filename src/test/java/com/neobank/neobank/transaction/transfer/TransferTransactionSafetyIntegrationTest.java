package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.SimulatedFailureException;
import com.neobank.neobank.transaction.TransactionIntegrationTestSupport;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class TransferTransactionSafetyIntegrationTest extends TransactionIntegrationTestSupport {

    @Autowired
    private TransferService transferService;

    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    @MockitoSpyBean
    private LedgerPostingService ledgerPostingService;

    @Test
    void failedTransferRollsBackBothBalancesEntriesTransactionAndIdempotencyRecord() {
        String idempotencyKey = "11111111111111111111111111111111";

        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        LedgerAccount targetLedger = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account targetAccount = Account.createNew(
                "98765432100123",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                targetLedger
        );

        accountRepository.save(targetAccount);
        creditAndSave(sourceAccount, new BigDecimal("1000.00"));

        TransferRequest request = new TransferRequest(
                new BigDecimal("500"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedFailureException("Simulated failure after flush");

        }).when(idempotencyServiceSpy)
                .save(any(IdempotencyRecord.class));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        ))
                .isInstanceOf(SimulatedFailureException.class)
                .hasMessage("Simulated failure after flush");

        Account loadedSourceAccount = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber())
                .orElseThrow();

        Account loadedTargetAccount = accountRepository.findByAccountNumber(targetAccount.getAccountNumber())
                .orElseThrow();

        assertThat(loadedSourceAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(loadedTargetAccount.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(idempotencyRecordRepository.count())
                .isZero();

        assertThat(bankTransactionRepository.count())
                .isZero();

        assertThat(ledgerEntryRepository.count())
                .isZero();
    }

    @Test
    void concurrentTransfersWithSameKeyApplyTransferExactlyOnce() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";
        long concurrencyTimeout = 20;

        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        LedgerAccount targetLedger = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account targetAccount = Account.createNew(
                "98765432100123",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                targetLedger
        );

        accountRepository.save(targetAccount);
        creditAndSave(sourceAccount, new BigDecimal("1000.00"));

        TransferRequest request = new TransferRequest(
                new BigDecimal("500"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothInitialIdempotencyRecordLookupsComplete = new CountDownLatch(2);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var result = (Optional<IdempotencyRecord>) invocation.callRealMethod();

            if (bothInitialIdempotencyRecordLookupsComplete.getCount() > 0) {
                assertThat(result)
                        .as("both original transfers should initially find no idempotency record")
                        .isEmpty();

                bothInitialIdempotencyRecordLookupsComplete.countDown();

                boolean bothLoaded = bothInitialIdempotencyRecordLookupsComplete.await(
                        concurrencyTimeout,
                        TimeUnit.SECONDS
                );

                assertThat(bothLoaded)
                        .as("Both original transfers should initially find no idempotency record")
                        .isTrue();
            }

            return result;
        }).when(idempotencyServiceSpy).findAndValidateRecord(
                eq(idempotencyKey),
                eq(sourceCustomer.getEmail()),
                anyString()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        TransferResponse response1;
        TransferResponse response2;

        try {
            Future<TransferResponse> transfer1 = executor.submit(() -> transferService.transfer(
                    request,
                    sourceAccount.getAccountNumber(),
                    sourceCustomer.getEmail(),
                    idempotencyKey
            ));

            Future<TransferResponse> transfer2 = executor.submit(() -> transferService.transfer(
                    request,
                    sourceAccount.getAccountNumber(),
                    sourceCustomer.getEmail(),
                    idempotencyKey
            ));

            response1 = transfer1.get(concurrencyTimeout, TimeUnit.SECONDS);
            response2 = transfer2.get(concurrencyTimeout, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(response1)
                .isEqualTo(response2);

        assertThat(response1.amount())
                .isEqualByComparingTo("500.00");

        assertThat(response1.sourceBalanceAfter())
                .isEqualByComparingTo("500.00");

        Account loadedSourceAccount = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber())
                .orElseThrow();

        Account loadedTargetAccount = accountRepository.findByAccountNumber(targetAccount.getAccountNumber())
                .orElseThrow();

        assertThat(loadedSourceAccount.getBalance())
                .isEqualByComparingTo("500.00");

        assertThat(loadedTargetAccount.getBalance())
                .isEqualByComparingTo("500.00");

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

    }

    @Test
    void concurrentTransfersWithDifferentKeysPreserveCorrectBalances() throws Exception {
        String idempotencyKey1 = "11111111111111111111111111111111";
        String idempotencyKey2 = "22222222222222222222222222222222";

        long concurrencyTimeout = 20;

        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        LedgerAccount targetLedger = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account targetAccount = Account.createNew(
                "98765432100123",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                targetLedger
        );

        accountRepository.save(targetAccount);

        creditAndSave(targetAccount, new BigDecimal("1000.00"));
        creditAndSave(sourceAccount, new BigDecimal("1000.00"));

        TransferRequest request1 = new TransferRequest(
                new BigDecimal("300"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        TransferRequest request2 = new TransferRequest(
                new BigDecimal("200"),
                sourceAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothInitialAccountLoadComplete = new CountDownLatch(2);
        CountDownLatch firstRequestCommitted = new CountDownLatch(1);

        AtomicInteger attemptCount = new AtomicInteger();

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);

            if (bothInitialAccountLoadComplete.getCount() > 0) {
                bothInitialAccountLoadComplete.countDown();

                boolean bothLoaded = bothInitialAccountLoadComplete.await(
                        concurrencyTimeout,
                        TimeUnit.SECONDS
                );

                assertThat(bothLoaded)
                        .as("both transfers should load the initial account version within the timeout")
                        .isTrue();
            }

            if (key.equals(idempotencyKey2)) {
                boolean firstTransferCompleted = firstRequestCommitted.await(
                        concurrencyTimeout,
                        TimeUnit.SECONDS
                );

                assertThat(firstTransferCompleted)
                        .as("The first transfer should commit before the second continues")
                        .isTrue();
            }

            attemptCount.getAndIncrement();

            return invocation.callRealMethod();
        }).when(idempotencyServiceSpy)
                .findAndValidateRecord(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        TransferResponse response1;
        TransferResponse response2;

        try {
            Future<TransferResponse> transfer1 = executor.submit(() -> transferService.transfer(
                    request1,
                    sourceAccount.getAccountNumber(),
                    sourceCustomer.getEmail(),
                    idempotencyKey1
            ));

            Future<TransferResponse> transfer2 = executor.submit(() -> transferService.transfer(
                    request2,
                    targetAccount.getAccountNumber(),
                    targetCustomer.getEmail(),
                    idempotencyKey2
            ));

            response1 = transfer1.get(concurrencyTimeout, TimeUnit.SECONDS);

            firstRequestCommitted.countDown();

            response2 = transfer2.get(concurrencyTimeout, TimeUnit.SECONDS);
        } finally {
            firstRequestCommitted.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(attemptCount.get())
                .isEqualTo(3);

        assertThat(response1.sourceBalanceAfter())
                .isEqualByComparingTo("700.00");

        assertThat(response1.amount())
                .isEqualByComparingTo(request1.amount());

        assertThat(response2.amount())
                .isEqualByComparingTo(request2.amount());

        assertThat(response2.sourceBalanceAfter())
                .isEqualByComparingTo("1100.00");

        Account loadedSourceAccount = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber())
                .orElseThrow();

        Account loadedTargetAccount = accountRepository.findByAccountNumber(targetAccount.getAccountNumber())
                .orElseThrow();

        assertThat(loadedSourceAccount.getBalance())
                .isEqualByComparingTo("900.00");

        assertThat(loadedTargetAccount.getBalance())
                .isEqualByComparingTo("1100.00");

        assertThat(accountRepository.count())
                .isEqualTo(2);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(2);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(4);

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(2);
    }

    @Test
    void failureWhilePostingDestinationCreditRollsBackSourceDebit() {
        String idempotencyKey = "11111111111111111111111111111111";

        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        LedgerAccount targetLedger = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account targetAccount = Account.createNew(
                "98765432100123",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                targetLedger
        );

        accountRepository.save(targetAccount);
        creditAndSave(sourceAccount, new BigDecimal("1000.00"));

        TransferRequest request = new TransferRequest(
                new BigDecimal("500"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        LedgerPostingService ledgerPostingServiceSpy = AopTestUtils.getUltimateTargetObject(ledgerPostingService);

        doThrow(new SimulatedFailureException("Simulated failure occurred"))
                .when(ledgerPostingServiceSpy)
                .post(any(BankTransaction.class), any(LedgerAccount.class), eq(EntryDirection.CREDIT), any(BigDecimal.class));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail(),
                idempotencyKey
        ))
                .isInstanceOf(SimulatedFailureException.class)
                .hasMessage("Simulated failure occurred");

        Account loadedSourceAccount = accountRepository.findByAccountNumber(sourceAccount.getAccountNumber())
                .orElseThrow();

        Account loadedTargetAccount = accountRepository.findByAccountNumber(targetAccount.getAccountNumber())
                .orElseThrow();

        assertThat(loadedSourceAccount.getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(loadedTargetAccount.getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(bankTransactionRepository.count())
                .isZero();

        assertThat(ledgerEntryRepository.count())
                .isZero();

        assertThat(idempotencyRecordRepository.count())
                .isZero();
    }
}
