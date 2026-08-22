package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.transaction.SimulatedFailureException;
import com.neobank.neobank.transaction.TransactionIntegrationTestSupport;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class DepositTransactionSafetyIntegrationTest extends TransactionIntegrationTestSupport {

    private static final String SECOND_IDEMPOTENCY_KEY = "22222222222222222222222222222222";
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 20;

    @Autowired
    private DepositService depositService;

    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    @Test
    void failedDepositRollsBackBalanceTransactionEntryAndIdempotencyRecord() {
        DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test");

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedFailureException("Simulated failure after flush");
        }).when(idempotencyServiceSpy).save(any(IdempotencyRecord.class));

        assertThatThrownBy(() -> depositService.deposit(
                request,
                account.getAccountNumber(),
                customer.getEmail(),
                IDEMPOTENCY_KEY
        ))
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
                .isEqualByComparingTo("0.00");
    }

    @Test
    void concurrentDepositsWithSameKeyApplyDepositExactlyOnce() throws Exception {
        String accountNumber = account.getAccountNumber();
        String customerEmail = customer.getEmail();
        DepositRequest request = new DepositRequest(new BigDecimal("100.00"), "Same key");

        CountDownLatch bothInitialLookupsFinished = new CountDownLatch(2);

        IdempotencyService idempotencyServiceSpy =
                AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<IdempotencyRecord> result =
                    (Optional<IdempotencyRecord>) invocation.callRealMethod();

            if (bothInitialLookupsFinished.getCount() > 0) {
                assertThat(result)
                        .as("both original deposits should initially find no idempotency record")
                        .isEmpty();
                bothInitialLookupsFinished.countDown();

                boolean bothDepositsArrived = bothInitialLookupsFinished.await(
                        CONCURRENCY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );

                assertThat(bothDepositsArrived)
                        .as("both deposits should reach the same point")
                        .isTrue();
            }

            return result;
        }).when(idempotencyServiceSpy).findAndValidateRecord(
                anyString(),
                eq(customerEmail),
                anyString()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        DepositResponse firstResponse;
        DepositResponse secondResponse;

        try {
            Future<DepositResponse> firstDeposit = executor.submit(() -> depositService.deposit(
                    request,
                    accountNumber,
                    customerEmail,
                    IDEMPOTENCY_KEY
            ));

            Future<DepositResponse> secondDeposit = executor.submit(() -> depositService.deposit(
                    request,
                    accountNumber,
                    customerEmail,
                    IDEMPOTENCY_KEY
            ));

            firstResponse = firstDeposit.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondResponse = secondDeposit.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(firstResponse)
                .isEqualTo(secondResponse);

        assertThat(firstResponse.amount())
                .isEqualByComparingTo("100.00");

        assertThat(firstResponse.balanceAfter())
                .isEqualByComparingTo("100.00");

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(1);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(1);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);

        var reloadedAccount = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow();

        assertThat(reloadedAccount.getBalance())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void concurrentDepositsWithDifferentKeysProduceCorrectFinalBalance() throws Exception {
        String accountNumber = account.getAccountNumber();
        String customerEmail = customer.getEmail();
        DepositRequest request = new DepositRequest(new BigDecimal("100.00"), "Different keys");

        CountDownLatch bothInitialLookupsFinished = new CountDownLatch(2);

        IdempotencyService idempotencyServiceSpy =
                AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<IdempotencyRecord> result =
                    (Optional<IdempotencyRecord>) invocation.callRealMethod();

            if (bothInitialLookupsFinished.getCount() > 0) {
                assertThat(result)
                        .as("both original deposits should initially find no idempotency record")
                        .isEmpty();
                bothInitialLookupsFinished.countDown();

                boolean bothDepositsArrived = bothInitialLookupsFinished.await(
                        CONCURRENCY_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );

                assertThat(bothDepositsArrived)
                        .as("both deposits should reach the same point")
                        .isTrue();
            }

            return result;
        }).when(idempotencyServiceSpy).findAndValidateRecord(
                anyString(),
                eq(customerEmail),
                anyString()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        DepositResponse firstResponse;
        DepositResponse secondResponse;

        try {
            Future<DepositResponse> firstDeposit = executor.submit(() -> depositService.deposit(
                    request,
                    accountNumber,
                    customerEmail,
                    IDEMPOTENCY_KEY
            ));

            Future<DepositResponse> secondDeposit = executor.submit(() -> depositService.deposit(
                    request,
                    accountNumber,
                    customerEmail,
                    SECOND_IDEMPOTENCY_KEY
            ));

            firstResponse = firstDeposit.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondResponse = secondDeposit.get(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(List.of(firstResponse.balanceAfter(), secondResponse.balanceAfter()))
                .containsExactlyInAnyOrder(
                        new BigDecimal("100.00"),
                        new BigDecimal("200.00")
                );

        assertThat(idempotencyRecordRepository.count()).isEqualTo(2);
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(bankTransactionRepository.count()).isEqualTo(2);

        var reloadedAccount = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow();

        assertThat(reloadedAccount.getBalance())
                .isEqualByComparingTo("200.00");
    }
}
