package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.SimulatedFailureException;
import com.neobank.neobank.transaction.TransactionIntegrationTestSupport;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class WithdrawalTransactionSafetyIntegrationTest extends TransactionIntegrationTestSupport {

    @Autowired
    private WithdrawalService withdrawalService;

    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    @Test
    void failedWithdrawalRollsBackBalanceTransactionEntryAndIdempotencyRecord() {
        String idempotencyKey = "11111111111111111111111111111111";

        creditAndSave(account, new BigDecimal("1000.00"));

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("200.00"), "Test");

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedFailureException("Simulated failure after flush");
        })
                .when(idempotencyServiceSpy).save(any(IdempotencyRecord.class));

        assertThatThrownBy(
                () -> withdrawalService.withdraw(
                        request,
                        account.getAccountNumber(),
                        customer.getEmail(),
                        idempotencyKey
                )
        )
                .isInstanceOf(SimulatedFailureException.class)
                .hasMessage("Simulated failure after flush");

        assertThat(idempotencyRecordRepository.count())
                .isZero();

        assertThat(ledgerEntryRepository.count())
                .isZero();

        assertThat(bankTransactionRepository.count())
                .isZero();

        var reloadedAccount = accountRepository.findByAccountNumber(account.getAccountNumber())
                .orElseThrow();

        assertThat(reloadedAccount.getBalance())
                .isEqualTo(new BigDecimal("1000.00"));

        var reloadedInternalAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY)
                .orElseThrow();

        assertThat(reloadedInternalAccount.getBalance())
                .isEqualByComparingTo("10000.00");
    }

    @Test
    void concurrentWithdrawalsWithSameKeyApplyWithdrawalExactlyOnce() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";
        long concurrencyTimeoutSeconds = 20;

        creditAndSave(account, new BigDecimal("1000.00"));

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("200.00"), "Test");

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothInitialIdempotencyLookupsComplete = new CountDownLatch(2);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<IdempotencyRecord> result = (Optional<IdempotencyRecord>) invocation.callRealMethod();

            if (bothInitialIdempotencyLookupsComplete.getCount() > 0) {
                assertThat(result)
                        .as("both original withdrawals should initially find no idempotency record")
                        .isEmpty();

                bothInitialIdempotencyLookupsComplete.countDown();

                boolean bothLookupsFinished = bothInitialIdempotencyLookupsComplete.await(
                        concurrencyTimeoutSeconds,
                        TimeUnit.SECONDS
                );

                assertThat(bothLookupsFinished)
                        .as("both initial idempotency lookups should finish within the timeout")
                        .isTrue();
            }
            return result;
        }).when(idempotencyServiceSpy).findAndValidateRecord(
                eq(idempotencyKey),
                eq(customer.getEmail()),
                anyString()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        WithdrawalResponse response1;
        WithdrawalResponse response2;

        try {
            Future<WithdrawalResponse> withdrawal1 = executor.submit(() -> withdrawalService.withdraw(
                    request,
                    account.getAccountNumber(),
                    customer.getEmail(),
                    idempotencyKey
            ));

            Future<WithdrawalResponse> withdrawal2 = executor.submit(() -> withdrawalService.withdraw(
                    request,
                    account.getAccountNumber(),
                    customer.getEmail(),
                    idempotencyKey
            ));

            response1 = withdrawal1.get(concurrencyTimeoutSeconds, TimeUnit.SECONDS);
            response2 = withdrawal2.get(concurrencyTimeoutSeconds, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(response1)
                .isEqualTo(response2);

        assertThat(response1.amount())
                .isEqualByComparingTo("200.00");

        assertThat(response1.balanceAfter())
                .isEqualByComparingTo("800.00");

        var reloadedAccount = accountRepository.findByAccountNumber(account.getAccountNumber())
                .orElseThrow();

        assertThat(reloadedAccount.getBalance())
                .isEqualByComparingTo("800.00");

        var reloadedInternalAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY)
                .orElseThrow();

        assertThat(reloadedInternalAccount.getBalance())
                .isEqualByComparingTo("9800.00");

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);
    }

    @Test
    void concurrentWithdrawalsWithDifferentKeysBothSucceedWhenFundsAreSufficient() throws Exception {
        String idempotencyKey1 = "11111111111111111111111111111111";
        String idempotencyKey2 = "22222222222222222222222222222222";
        long concurrencyTimeoutSeconds = 20;

        creditAndSave(account, new BigDecimal("1000.00"));

        WithdrawalRequest request1 = new WithdrawalRequest(new BigDecimal("200.00"), "Test");
        WithdrawalRequest request2 = new WithdrawalRequest(new BigDecimal("300.00"), "Test");

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothLoadedTheSameAccountVersion = new CountDownLatch(2);
        CountDownLatch firstRequestCommitted = new CountDownLatch(1);

        doAnswer(invocation -> {
            String key = invocation.getArgument(0, String.class);

            if (bothLoadedTheSameAccountVersion.getCount() > 0) {
                bothLoadedTheSameAccountVersion.countDown();
                boolean bothLoaded = bothLoadedTheSameAccountVersion.await(
                        concurrencyTimeoutSeconds,
                        TimeUnit.SECONDS
                );
                assertThat(bothLoaded)
                        .as("both withdrawals should load the initial account version within the timeout")
                        .isTrue();
            }

            if (key.equals(idempotencyKey2)) {
                boolean firstCommitted = firstRequestCommitted.await(
                        concurrencyTimeoutSeconds,
                        TimeUnit.SECONDS
                );

                assertThat(firstCommitted)
                        .as("the first withdrawal should commit before the second continues")
                        .isTrue();
            }

            return invocation.callRealMethod();
        }).when(idempotencyServiceSpy).findAndValidateRecord(anyString(), eq(customer.getEmail()), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        WithdrawalResponse response1;
        WithdrawalResponse response2;

        try {
            Future<WithdrawalResponse> withdrawal1 = executor.submit(() -> withdrawalService.withdraw(
                    request1,
                    account.getAccountNumber(),
                    customer.getEmail(),
                    idempotencyKey1
            ));

            Future<WithdrawalResponse> withdrawal2 = executor.submit(() -> withdrawalService.withdraw(
                    request2,
                    account.getAccountNumber(),
                    customer.getEmail(),
                    idempotencyKey2
            ));

            response1 = withdrawal1.get(concurrencyTimeoutSeconds, TimeUnit.SECONDS);

            firstRequestCommitted.countDown();

            response2 = withdrawal2.get(concurrencyTimeoutSeconds, TimeUnit.SECONDS);
        } finally {
            firstRequestCommitted.countDown();
            executor.shutdownNow();
        }

        assertThat(response1.balanceAfter())
                .isEqualByComparingTo("800.00");

        assertThat(response2.balanceAfter())
                .isEqualByComparingTo("500.00");

        assertThat(response1.accountNumber())
                .isEqualTo(response2.accountNumber());

        var reloadedAccount = accountRepository.findByAccountNumber(account.getAccountNumber())
                .orElseThrow();

        assertThat(reloadedAccount.getBalance())
                .isEqualByComparingTo("500.00");

        var reloadedInternalAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY)
                .orElseThrow();

        assertThat(reloadedInternalAccount.getBalance())
                .isEqualByComparingTo("9500.00");

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(2);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(2);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(4);
    }

    @Test
    void concurrentWithdrawalsWithDifferentKeysAllowOnlyOneWhenCombinedAmountExceedsBalance() throws Exception {
        String idempotencyKey1 = "11111111111111111111111111111111";
        String idempotencyKey2 = "22222222222222222222222222222222";
        long concurrencyTimeoutSeconds = 20;

        BigDecimal initialBalance = new BigDecimal("1000.00");
        creditAndSave(account, initialBalance);

        WithdrawalRequest request1 = new WithdrawalRequest(new BigDecimal("600.00"), "Test");
        WithdrawalRequest request2 = new WithdrawalRequest(new BigDecimal("500.00"), "Test");

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothLoadedTheSameAccountVersion = new CountDownLatch(2);

        AtomicInteger attemptCount = new AtomicInteger();

        doAnswer(invocation -> {
            attemptCount.getAndIncrement();

            if (bothLoadedTheSameAccountVersion.getCount() > 0) {
                bothLoadedTheSameAccountVersion.countDown();

                boolean bothLoadingFinished = bothLoadedTheSameAccountVersion.await(concurrencyTimeoutSeconds, TimeUnit.SECONDS);

                assertThat(bothLoadingFinished)
                        .isTrue();
            }

            return invocation.callRealMethod();
        }).when(idempotencyServiceSpy)
                .findAndValidateRecord(anyString(), eq(customer.getEmail()), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Object> outcomes = new ArrayList<>(2);

        try {
            Future<WithdrawalResponse> withdrawal1 = executor.submit(() -> withdrawalService.withdraw(
                    request1,
                    account.getAccountNumber(),
                    customer.getEmail(),
                    idempotencyKey1
            ));

            Future<WithdrawalResponse> withdrawal2 = executor.submit(() -> withdrawalService.withdraw(
                    request2,
                    account.getAccountNumber(),
                    customer.getEmail(),
                    idempotencyKey2
            ));

            for (Future<WithdrawalResponse> future : List.of(withdrawal1, withdrawal2)) {
                try {
                    outcomes.add(future.get(concurrencyTimeoutSeconds, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    outcomes.add(exception.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        WithdrawalResponse successfulResponse = outcomes.stream()
                .filter(response -> response instanceof WithdrawalResponse)
                .map(response -> (WithdrawalResponse) response)
                .findFirst()
                .orElseThrow();

        InsufficientFundsException exceptionResponse = outcomes.stream()
                .filter(response -> response instanceof InsufficientFundsException)
                .map(response -> (InsufficientFundsException) response)
                .findFirst()
                .orElseThrow();

        assertThat(successfulResponse.balanceAfter())
                .isEqualByComparingTo(initialBalance.subtract(successfulResponse.amount()));

        assertThat(exceptionResponse.getMessage())
                .isEqualTo("Insufficient funds");

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        assertThat(attemptCount.get())
                .isEqualTo(3);

        var reloadedAccount = accountRepository.findByAccountNumber(account.getAccountNumber())
                .orElseThrow();

        assertThat(reloadedAccount.getBalance())
                .isEqualByComparingTo(initialBalance.subtract(successfulResponse.amount()));

        var reloadedInternalAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY)
                .orElseThrow();

        assertThat(reloadedInternalAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("10000.00").subtract(successfulResponse.amount()));
    }
}
