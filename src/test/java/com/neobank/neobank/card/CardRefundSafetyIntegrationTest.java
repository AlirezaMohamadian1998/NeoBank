package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardRefundResponse;
import com.neobank.neobank.card.exception.CardPurchaseNotRefundableException;
import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.SimulatedFailureException;
import com.neobank.neobank.transaction.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class CardRefundSafetyIntegrationTest extends DebitCardIntegrationTestSupport {

    private static final String CARD_REFERENCE = "2".repeat(32);
    private static final String MERCHANT_EMAIL = "merchant@example.com";
    private static final String MERCHANT_ACCOUNT_NUMBER = "98".repeat(7);
    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private CardPaymentService cardPaymentService;

    @Autowired
    private CardRefundService cardRefundService;

    @Autowired
    private MySQLContainer mysql;

    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    private String purchaseReference;

    @BeforeEach
    void setUpCardRefundSafetyFixture() {
        account.getLedgerAccount().credit(new BigDecimal("1000.00"));
        ledgerAccountRepository.save(account.getLedgerAccount());

        DebitCard debitCard = DebitCard.createNew(
                CARD_REFERENCE,
                "1234",
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                account,
                TestCardLimits.generous()
        );
        debitCard.activateCard(YearMonth.now(clock));
        debitCardRepository.save(debitCard);

        Customer merchantCustomer = customerRepository.save(
                Customer.createNew(MERCHANT_EMAIL, "{bcrypt}password-hash", "Merchant Owner")
        );

        accountRepository.save(Account.createNew(
                MERCHANT_ACCOUNT_NUMBER,
                "merchant",
                AccountType.CURRENT,
                merchantCustomer,
                LedgerAccount.createNew("5".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY)
        ));

        purchaseReference = cardPaymentService.makePurchase(
                CARD_REFERENCE,
                new CardPurchaseRequest(MERCHANT_ACCOUNT_NUMBER, CurrencyCode.TRY, new BigDecimal("250.00"), null, "Test merchant"),
                "c".repeat(32),
                customer.getEmail()
        ).transactionReference();
    }

    @Test
    void concurrentRefundsWithDifferentKeysRefundExactlyOnce() throws Exception {
        int requests = 4;

        holdFirstRefundUntilOthersWaitOnLock(requests - 1);

        List<Future<CardRefundResponse>> futures = submitConcurrently(requests, i -> "%032x".formatted(i + 10));

        int succeeded = 0;
        List<Throwable> failures = new ArrayList<>();

        for(Future<CardRefundResponse> future : futures) {
            try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }

        assertThat(succeeded)
                .isOne();

        assertThat(failures)
                .hasSize(requests - 1)
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(CardPurchaseNotRefundableException.class)
                        .hasMessage("Card purchase is already refunded"));

        assertRefundedExactlyOnce();
    }

    @Test
    void concurrentRefundsWithSameKeyReplayTheWinningRefund() throws Exception {
        holdFirstRefundUntilOthersWaitOnLock(1);

        List<Future<CardRefundResponse>> futures = submitConcurrently(2, i -> "a".repeat(32));

        CardRefundResponse first = futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CardRefundResponse second = futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(second.refundReference())
                .as("the request that waited on the lock must replay the stored result, not get a 409")
                .isEqualTo(first.refundReference());

        assertThat(second.debitedAmount())
                .isEqualByComparingTo(first.debitedAmount());

        assertThat(second.balanceAfter())
                .isEqualByComparingTo(first.balanceAfter());

        assertRefundedExactlyOnce();
    }

    @Test
    void failedRefundRollsBackEverythingAndLeavesPurchaseRefundable() {
        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedFailureException("Simulated failure after flush");
        }).when(idempotencyServiceSpy)
                .save(any(IdempotencyRecord.class));

        assertThatThrownBy(() -> cardRefundService.refundPurchase(
                MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL
        ))
                .isInstanceOf(SimulatedFailureException.class);

        assertThat(bankTransactionRepository.findByReference(purchaseReference).orElseThrow().getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("750.00");

        assertThat(balanceOf(MERCHANT_ACCOUNT_NUMBER))
                .isEqualByComparingTo("250.00");

        assertThat(cardRefundDetailsRepository.count())
                .isZero();

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        assertThat(idempotencyRecordRepository.count())
                .as("only the purchase's record")
                .isOne();
    }

    /**
     * Makes the refund that holds the purchase lock pause just before it commits, until MySQL reports
     * the given number of other transactions blocked on a row lock. This guarantees a real overlap
     * instead of requests that happen to run one after another.
     */
    private void holdFirstRefundUntilOthersWaitOnLock(int expectedWaiters) {
        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);
        AtomicBoolean firstHolder = new AtomicBoolean(true);

        doAnswer(invocation -> {
            IdempotencyRecord record = invocation.getArgument(0);

            if(!record.getResultReference().equals(purchaseReference) && firstHolder.getAndSet(false)) {
                awaitLockWaiters(expectedWaiters);
            }

            return invocation.callRealMethod();
        }).when(idempotencyServiceSpy)
                .save(any(IdempotencyRecord.class));
    }

    /**
     * Polls MySQL's lock-wait table over a separate root connection: the application's test user
     * has no privileges on performance_schema.
     */
    private void awaitLockWaiters(int expectedWaiters) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(TIMEOUT_SECONDS));

        try (Connection rootConnection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             PreparedStatement statement = rootConnection.prepareStatement(
                     "select count(*) from performance_schema.data_lock_waits"
             )) {

            while(Instant.now().isBefore(deadline)) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();

                    if(resultSet.getInt(1) >= expectedWaiters) {
                        return;
                    }
                }

                Thread.sleep(20);
            }
        }

        throw new IllegalStateException("Expected " + expectedWaiters + " transactions waiting on the purchase lock");
    }

    private List<Future<CardRefundResponse>> submitConcurrently(int requests, IntFunction<String> keyForRequest) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CardRefundResponse>> futures = new ArrayList<>();

        for(int i = 0; i < requests; i++) {
            String idempotencyKey = keyForRequest.apply(i);

            futures.add(executor.submit(() -> {
                start.await();
                return cardRefundService.refundPurchase(MERCHANT_ACCOUNT_NUMBER, purchaseReference, idempotencyKey, MERCHANT_EMAIL);
            }));
        }

        start.countDown();
        executor.shutdown();

        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("all refund requests should finish within the timeout")
                .isTrue();

        return futures;
    }

    private void assertRefundedExactlyOnce() {
        assertThat(bankTransactionRepository.findByReference(purchaseReference).orElseThrow().getStatus())
                .isSameAs(TransactionStatus.REFUNDED);

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("1000.00");

        assertThat(balanceOf(MERCHANT_ACCOUNT_NUMBER))
                .isEqualByComparingTo("0.00");

        assertThat(cardRefundDetailsRepository.count())
                .isOne();

        assertThat(bankTransactionRepository.count())
                .isEqualTo(2);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(4);
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }
}
