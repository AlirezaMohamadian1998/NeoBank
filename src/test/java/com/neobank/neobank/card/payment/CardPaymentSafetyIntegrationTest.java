package com.neobank.neobank.card.payment;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.debit.DebitCard;
import com.neobank.neobank.card.payment.dto.CardPurchaseRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.ProviderRateCache;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.SimulatedFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

class CardPaymentSafetyIntegrationTest extends CardIntegrationTestSupport {

    @Autowired
    private CardPaymentService cardPaymentService;

    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    @MockitoSpyBean
    private LedgerPostingService ledgerPostingService;

    @MockitoBean
    private ProviderRateCache providerRateCache;

    @BeforeEach
    void setUpCardPaymentSafetyFixture() {
        account.getLedgerAccount().credit(new BigDecimal("1000.00"));
        ledgerAccountRepository.save(account.getLedgerAccount());

        internalAccountRepository.save(createSettlementAccount("9".repeat(32), CurrencyCode.TRY));
        internalAccountRepository.save(createSettlementAccount("8".repeat(32), CurrencyCode.USD));

        DebitCard debitCard = DebitCard.createNew(
                "2".repeat(32),
                "1234",
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                account,
                TestCardLimits.generous()
        );
        debitCard.activateCard(YearMonth.now(clock));

        debitCardRepository.save(debitCard);
    }

    @Test
    void failedPurchaseRollsBackBalancesEntriesTransactionPurchaseDetailsAndIdempotencyRecord() {
        String idempotencyKey = "1".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("500.00"),
                "Test",
                "Test merchant"
        );

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedFailureException("Simulated failure after flush");
        }).when(idempotencyServiceSpy)
                .save(any(IdempotencyRecord.class));

        assertThatThrownBy(() -> cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                idempotencyKey,
                customer.getEmail()
        ))
                .isInstanceOf(SimulatedFailureException.class)
                .hasMessage("Simulated failure after flush");

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY).orElseThrow().getBalance())
                .isEqualByComparingTo("10000.00");

        assertThat(cardPurchaseDetailsRepository.count())
                .isZero();

        assertThat(bankTransactionRepository.count())
                .isZero();

        assertThat(ledgerEntryRepository.count())
                .isZero();

        assertThat(idempotencyRecordRepository.count())
                .isZero();
    }

    @Test
    void failureWhilePostingMerchantCreditRollsBackCardDebit() {
        String idempotencyKey = "1".repeat(32);

        Customer merchantCustomer = customerRepository.save(
                Customer.createNew(
                        "merchant@example.com",
                        "{bcrypt}raw-password321",
                        "Lovelace Ada"
                )
        );

        Account merchantAccount = accountRepository.save(
                Account.createNew(
                        "98".repeat(7),
                        "merchant",
                        AccountType.CURRENT,
                        merchantCustomer,
                        LedgerAccount.createNew(
                                "3".repeat(32),
                                LedgerAccountType.LIABILITY,
                                CurrencyCode.TRY
                        )
                )
        );

        CardPurchaseRequest request = new CardPurchaseRequest(
                merchantAccount.getAccountNumber(),
                CurrencyCode.TRY,
                new BigDecimal("500.00"),
                "Test",
                "Test merchant"
        );

        LedgerPostingService ledgerPostingServiceSpy = AopTestUtils.getUltimateTargetObject(ledgerPostingService);

        doThrow(new SimulatedFailureException("Simulated failure occurred"))
                .when(ledgerPostingServiceSpy)
                .post(any(BankTransaction.class), any(LedgerAccount.class), eq(EntryDirection.CREDIT), any(BigDecimal.class));

        assertThatThrownBy(() -> cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                idempotencyKey,
                customer.getEmail()
        ))
                .isInstanceOf(SimulatedFailureException.class)
                .hasMessage("Simulated failure occurred");

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(accountRepository.findByAccountNumber(merchantAccount.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");

        assertThat(cardPurchaseDetailsRepository.count())
                .isZero();

        assertThat(bankTransactionRepository.count())
                .isZero();

        assertThat(ledgerEntryRepository.count())
                .isZero();

        assertThat(idempotencyRecordRepository.count())
                .isZero();
    }

    @Test
    void failedCrossCurrencyPurchaseRollsBackFxSnapshotWithTransaction() {
        String idempotencyKey = "1".repeat(32);

        FxProviderRates providerRates = new FxProviderRates(
                "test-provider",
                CurrencyCode.USD,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.EUR, new BigDecimal("0.85"),
                        CurrencyCode.GBP, new BigDecimal("0.75"),
                        CurrencyCode.TRY, new BigDecimal("40.00")
                ),
                Instant.now()
        );

        given(providerRateCache.getLatestRates())
                .willReturn(providerRates);

        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.USD,
                new BigDecimal("10.00"),
                "Test",
                "Test merchant"
        );

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SimulatedFailureException("Simulated failure after flush");
        }).when(idempotencyServiceSpy)
                .save(any(IdempotencyRecord.class));

        assertThatThrownBy(() -> cardPaymentService.makePurchase(
                "2".repeat(32),
                request,
                idempotencyKey,
                customer.getEmail()
        ))
                .isInstanceOf(SimulatedFailureException.class)
                .hasMessage("Simulated failure after flush");

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.00");

        assertThat(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD).orElseThrow().getBalance())
                .isEqualByComparingTo("10000.00");

        assertThat(bankTransactionRepository.count())
                .isZero();

        assertThat(ledgerEntryRepository.count())
                .isZero();

        assertThat(cardPurchaseDetailsRepository.count())
                .isZero();

        assertThat(idempotencyRecordRepository.count())
                .isZero();
    }

    @Test
    void concurrentPurchasesWithSameKeyChargeCardExactlyOnce() throws Exception {
        String idempotencyKey = "1".repeat(32);
        long concurrencyTimeout = 20;

        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("500.00"),
                "Test",
                "Test merchant"
        );

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothInitialIdempotencyLookupsComplete = new CountDownLatch(2);
        AtomicInteger attemptCount = new AtomicInteger();

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<IdempotencyRecord> result = (Optional<IdempotencyRecord>) invocation.callRealMethod();

            if (bothInitialIdempotencyLookupsComplete.getCount() > 0) {
                assertThat(result)
                        .as("Both original purchases should initially find no idempotency record")
                        .isEmpty();

                bothInitialIdempotencyLookupsComplete.countDown();

                boolean bothLoaded = bothInitialIdempotencyLookupsComplete.await(
                        concurrencyTimeout,
                        TimeUnit.SECONDS
                );

                assertThat(bothLoaded)
                        .as("Both original purchases should reach the idempotency lookup within the timeout")
                        .isTrue();
            }

            attemptCount.getAndIncrement();

            return result;
        }).when(idempotencyServiceSpy).findAndValidateRecord(
                eq(idempotencyKey),
                eq(customer.getEmail()),
                anyString()
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CardPurchaseResponse response1;
        CardPurchaseResponse response2;

        try {
            Future<CardPurchaseResponse> purchase1 = executor.submit(() -> cardPaymentService.makePurchase(
                    "2".repeat(32),
                    request,
                    idempotencyKey,
                    customer.getEmail()
            ));

            Future<CardPurchaseResponse> purchase2 = executor.submit(() -> cardPaymentService.makePurchase(
                    "2".repeat(32),
                    request,
                    idempotencyKey,
                    customer.getEmail()
            ));

            response1 = purchase1.get(concurrencyTimeout, TimeUnit.SECONDS);
            response2 = purchase2.get(concurrencyTimeout, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(attemptCount.get())
                .isEqualTo(3);

        assertThat(response1)
                .isEqualTo(response2);

        assertThat(response1.debitedAmount())
                .isEqualByComparingTo("500.00");

        assertThat(response1.balanceAfter())
                .isEqualByComparingTo("500.00");

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.00");

        assertThat(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY).orElseThrow().getBalance())
                .isEqualByComparingTo("9500.00");

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        assertThat(cardPurchaseDetailsRepository.count())
                .isOne();

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);
    }

    @Test
    void concurrentPurchasesWithDifferentKeysPreserveCorrectBalances() throws Exception {
        String idempotencyKey1 = "1".repeat(32);
        String idempotencyKey2 = "5".repeat(32);

        long concurrencyTimeout = 20;

        CardPurchaseRequest request1 = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("300.00"),
                "Test",
                "Test merchant"
        );

        CardPurchaseRequest request2 = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("200.00"),
                "Test",
                "Test merchant"
        );

        IdempotencyService idempotencyServiceSpy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothInitialAccountLoadComplete = new CountDownLatch(2);
        CountDownLatch firstRequestCommitted = new CountDownLatch(1);

        AtomicInteger attemptCount = new AtomicInteger();

        // Both purchases load the same funding and settlement ledgers, then the second waits for the first
        // to commit, so its stale @Version causes an optimistic lock failure and a retry
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);

            if (bothInitialAccountLoadComplete.getCount() > 0) {
                bothInitialAccountLoadComplete.countDown();

                boolean bothLoaded = bothInitialAccountLoadComplete.await(
                        concurrencyTimeout,
                        TimeUnit.SECONDS
                );

                assertThat(bothLoaded)
                        .as("Both purchases should load the initial account version within the timeout")
                        .isTrue();
            }

            if (key.equals(idempotencyKey2)) {
                boolean firstPurchaseCompleted = firstRequestCommitted.await(
                        concurrencyTimeout,
                        TimeUnit.SECONDS
                );

                assertThat(firstPurchaseCompleted)
                        .as("The first purchase should commit before the second continues")
                        .isTrue();
            }

            attemptCount.getAndIncrement();

            return invocation.callRealMethod();
        }).when(idempotencyServiceSpy)
                .findAndValidateRecord(anyString(), anyString(), anyString());

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CardPurchaseResponse response1;
        CardPurchaseResponse response2;

        try {
            Future<CardPurchaseResponse> purchase1 = executor.submit(() -> cardPaymentService.makePurchase(
                    "2".repeat(32),
                    request1,
                    idempotencyKey1,
                    customer.getEmail()
            ));

            Future<CardPurchaseResponse> purchase2 = executor.submit(() -> cardPaymentService.makePurchase(
                    "2".repeat(32),
                    request2,
                    idempotencyKey2,
                    customer.getEmail()
            ));

            response1 = purchase1.get(concurrencyTimeout, TimeUnit.SECONDS);

            firstRequestCommitted.countDown();

            response2 = purchase2.get(concurrencyTimeout, TimeUnit.SECONDS);
        } finally {
            firstRequestCommitted.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(attemptCount.get())
                .isEqualTo(3);

        assertThat(response1.debitedAmount())
                .isEqualByComparingTo(request1.amount());

        assertThat(response1.balanceAfter())
                .isEqualByComparingTo("700.00");

        assertThat(response2.debitedAmount())
                .isEqualByComparingTo(request2.amount());

        assertThat(response2.balanceAfter())
                .isEqualByComparingTo("500.00");

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("500.00");

        assertThat(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY).orElseThrow().getBalance())
                .isEqualByComparingTo("9500.00");

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(2);

        assertThat(cardPurchaseDetailsRepository.count())
                .isEqualTo(2);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(2);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(4);
    }
}
