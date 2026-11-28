package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.Card;
import com.neobank.neobank.card.debit.DebitCard;
import com.neobank.neobank.card.payment.CardPaymentService;
import com.neobank.neobank.card.payment.dto.CardPurchaseRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

class CardSpendingLimitSafetyIntegrationTest extends CardIntegrationTestSupport {

    private static final String CARD_REFERENCE = "2".repeat(32);

    private static final String FIRST_KEY = "1".repeat(32);

    private static final String SECOND_KEY = "5".repeat(32);

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private CardPaymentService cardPaymentService;

    @MockitoSpyBean
    private IdempotencyService idempotencyService;

    @MockitoSpyBean
    private CardDailySpendService cardDailySpendService;

    private DebitCard debitCard;

    @BeforeEach
    void setUpCardWithSmallLimits() {
        account.getLedgerAccount().credit(new BigDecimal("1000.00"));
        ledgerAccountRepository.save(account.getLedgerAccount());

        LedgerAccount settlementLedger = LedgerAccount.createNew("9".repeat(32), LedgerAccountType.ASSET, CurrencyCode.TRY);
        settlementLedger.debit(new BigDecimal("10000.00"));
        internalAccountRepository.save(InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, settlementLedger));

        DebitCard newDebitCard = DebitCard.createNew(
                CARD_REFERENCE,
                "1234",
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                account,
                CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00"))
        );
        newDebitCard.activateCard(YearMonth.now(clock));

        debitCard = debitCardRepository.save(newDebitCard);
    }

    @Test
    void concurrentFirstPurchasesOfTheDayBothSucceedWithOneRow() throws Exception {
        AtomicInteger raceCount = countThrown(CardDailySpendRaceException.class);
        AtomicInteger attemptCount = gateSecondPurchaseBehindFirstCommit();

        PurchaseOutcome outcome = purchaseConcurrently("60.00", "70.00");

        assertThat(outcome.first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).debitedAmount()).isEqualByComparingTo("60.00");
        assertThat(outcome.second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).debitedAmount()).isEqualByComparingTo("70.00");

        assertThat(raceCount.get()).isOne();
        assertThat(attemptCount.get()).isEqualTo(3);

        assertThat(cardDailySpendRepository.count()).isOne();
        assertThat(spentToday()).isEqualByComparingTo("130.00");
        assertThat(balanceOf()).isEqualByComparingTo("870.00");
    }

    @Test
    void concurrentPurchasesCannotTogetherExceedDailyLimit() throws Exception {
        CardDailySpend todaysSpend = CardDailySpend.createNew(debitCard, LocalDate.now(clock));
        todaysSpend.add(new BigDecimal("100.00"), debitCard.getSpendingLimits());
        cardDailySpendRepository.save(todaysSpend);

        AtomicInteger conflictCount = countThrown(ConcurrencyFailureException.class);
        AtomicInteger attemptCount = gateSecondPurchaseBehindFirstCommit();

        PurchaseOutcome outcome = purchaseConcurrently("100.00", "100.00");

        assertThat(outcome.first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).debitedAmount()).isEqualByComparingTo("100.00");

        assertThatThrownBy(() -> outcome.second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(CardLimitExceededException.class);

        assertThat(conflictCount.get()).isOne();
        assertThat(attemptCount.get()).isEqualTo(3);

        assertThat(spentToday()).isEqualByComparingTo("200.00");
        assertThat(balanceOf()).isEqualByComparingTo("900.00");
        assertThat(bankTransactionRepository.count()).isOne();
    }

    private AtomicInteger gateSecondPurchaseBehindFirstCommit() {
        IdempotencyService spy = AopTestUtils.getUltimateTargetObject(idempotencyService);

        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicInteger attemptCount = new AtomicInteger();

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);

            if (bothStarted.getCount() > 0) {
                bothStarted.countDown();
                assertThat(bothStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("Both purchases should start within the timeout")
                        .isTrue();
            }

            if (key.equals(SECOND_KEY)) {
                assertThat(firstCommitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("The first purchase should commit before the second continues")
                        .isTrue();
            }

            attemptCount.getAndIncrement();

            return invocation.callRealMethod();
        }).when(spy).findAndValidateRecord(anyString(), anyString(), anyString());

        return attemptCount;
    }

    private AtomicInteger countThrown(Class<? extends Throwable> type) {
        CardDailySpendService spy = AopTestUtils.getUltimateTargetObject(cardDailySpendService);
        AtomicInteger count = new AtomicInteger();

        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (Throwable thrown) {
                if (type.isInstance(thrown)) {
                    count.incrementAndGet();
                }
                throw thrown;
            }
        }).when(spy).recordSpend(any(Card.class), any(LocalDate.class), any(BigDecimal.class));

        return count;
    }

    private final CountDownLatch firstCommitted = new CountDownLatch(1);

    private PurchaseOutcome purchaseConcurrently(String firstAmount, String secondAmount) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CardPurchaseResponse> first = executor.submit(() -> purchase(firstAmount, FIRST_KEY));
            Future<CardPurchaseResponse> second = executor.submit(() -> purchase(secondAmount, SECOND_KEY));

            try {
                first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                firstCommitted.countDown();
            }

            try {
                second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
            }

            return new PurchaseOutcome(first, second);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private CardPurchaseResponse purchase(String amount, String idempotencyKey) {
        return cardPaymentService.makePurchase(
                CARD_REFERENCE,
                new CardPurchaseRequest(null, CurrencyCode.TRY, new BigDecimal(amount), null, "Test merchant"),
                idempotencyKey,
                customer.getEmail()
        );
    }

    private BigDecimal spentToday() {
        return cardDailySpendRepository.findByCardAndSpendDate(debitCard, LocalDate.now(clock))
                .orElseThrow()
                .getAmountSpent();
    }

    private BigDecimal balanceOf() {
        return accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance();
    }

    private record PurchaseOutcome(Future<CardPurchaseResponse> first, Future<CardPurchaseResponse> second) {
    }
}
