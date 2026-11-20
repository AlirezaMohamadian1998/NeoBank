package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.ProviderRateCache;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CardSpendingLimitIntegrationTest extends DebitCardIntegrationTestSupport {

    private static final String CARD_REFERENCE = "2".repeat(32);

    @MockitoBean
    private ProviderRateCache providerRateCache;

    private DebitCard debitCard;

    @BeforeEach
    void setUpCardWithSmallLimits() {
        account.getLedgerAccount().credit(new BigDecimal("10000.00"));
        ledgerAccountRepository.save(account.getLedgerAccount());

        internalAccountRepository.save(createSettlementAccount("9".repeat(32), CurrencyCode.TRY));
        internalAccountRepository.save(createSettlementAccount("8".repeat(32), CurrencyCode.USD));

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
    void purchaseOverPerTransactionLimitIsRejectedWithoutPersistingAnything() throws Exception {
        purchase(null, "100.01", CurrencyCode.TRY, "1".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card limit exceeded"))
                .andExpect(jsonPath("$.detail").value("Purchase exceeds the card's per-transaction limit"));

        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("10000.00");
        assertThat(bankTransactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(cardPurchaseDetailsRepository.count()).isZero();
        assertThat(idempotencyRecordRepository.count()).isZero();
        assertThat(cardDailySpendRepository.count()).isZero();
    }

    @Test
    void purchasesAddUpUntilDailyLimitIsReached() throws Exception {
        purchase(null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());
        purchase(null, "100.00", CurrencyCode.TRY, "3".repeat(32)).andExpect(status().isCreated());

        purchase(null, "50.01", CurrencyCode.TRY, "4".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Card limit exceeded"))
                .andExpect(jsonPath("$.detail").value("Purchase exceeds the card's daily limit"));

        assertThat(spentToday()).isEqualByComparingTo("200.00");

        purchase(null, "50.00", CurrencyCode.TRY, "5".repeat(32)).andExpect(status().isCreated());

        assertThat(spentToday()).isEqualByComparingTo("250.00");
        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("9750.00");
        assertThat(cardDailySpendRepository.count()).isOne();
    }

    @Test
    void replayedPurchaseIsNotCountedTwice() throws Exception {
        purchase(null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());
        purchase(null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());

        assertThat(spentToday()).isEqualByComparingTo("100.00");
    }

    @Test
    void crossCurrencyPurchaseIsCountedInFundingCurrency() throws Exception {
        stubTryPerUsd("40.00");

        purchase(null, "2.00", CurrencyCode.USD, "1".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitedAmount").value(80.00));

        assertThat(spentToday()).isEqualByComparingTo("80.00");

        purchase(null, "3.00", CurrencyCode.USD, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Purchase exceeds the card's per-transaction limit"));
    }

    @Test
    void spendingFromPreviousDayDoesNotCountTowardToday() throws Exception {
        CardDailySpend yesterday = CardDailySpend.createNew(debitCard, LocalDate.now(clock).minusDays(1));
        yesterday.add(new BigDecimal("100.00"), debitCard.getSpendingLimits());
        yesterday.add(new BigDecimal("100.00"), debitCard.getSpendingLimits());
        yesterday.add(new BigDecimal("50.00"), debitCard.getSpendingLimits());
        cardDailySpendRepository.save(yesterday);

        purchase(null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());

        assertThat(spentToday()).isEqualByComparingTo("100.00");
        assertThat(cardDailySpendRepository.count()).isEqualTo(2);
    }

    @Test
    void refundDoesNotGiveBackDailyLimit() throws Exception {
        Customer merchantCustomer = customerRepository.save(
                Customer.createNew("merchant@example.com", "{bcrypt}password-hash", "Grace Hopper")
        );

        accountRepository.save(
                Account.createNew(
                        "98".repeat(7),
                        "merchant",
                        AccountType.CURRENT,
                        merchantCustomer,
                        LedgerAccount.createNew("3".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY)
                )
        );

        String body = purchase("98".repeat(7), "100.00", CurrencyCode.TRY, "1".repeat(32))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String purchaseReference = objectMapper.readValue(body, CardPurchaseResponse.class).transactionReference();

        mockMvc.perform(post("/api/accounts/{merchantAccountNumber}/card-refunds/{purchaseReference}", "98".repeat(7), purchaseReference)
                        .header("Idempotency-Key", "a".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(merchantCustomer.getEmail()))))
                .andExpect(status().isCreated());

        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("10000.00");
        assertThat(spentToday()).isEqualByComparingTo("100.00");
    }

    private ResultActions purchase(String merchantAccountNumber, String amount, CurrencyCode currency, String idempotencyKey) throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                merchantAccountNumber,
                currency,
                new BigDecimal(amount),
                null,
                "Test merchant"
        );

        return mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", CARD_REFERENCE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))));
    }

    private BigDecimal spentToday() {
        return cardDailySpendRepository.findByCardAndSpendDate(debitCard, LocalDate.now(clock))
                .orElseThrow()
                .getAmountSpent();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private void stubTryPerUsd(String tryPerUsd) {
        given(providerRateCache.getLatestRates())
                .willReturn(new FxProviderRates(
                        "test-provider",
                        CurrencyCode.USD,
                        Map.of(
                                CurrencyCode.USD, BigDecimal.ONE,
                                CurrencyCode.EUR, new BigDecimal("0.85"),
                                CurrencyCode.GBP, new BigDecimal("0.75"),
                                CurrencyCode.TRY, new BigDecimal(tryPerUsd)
                        ),
                        Instant.now()
                ));
    }

    private InternalAccount createSettlementAccount(String ledgerReference, CurrencyCode currency) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(ledgerReference, LedgerAccountType.ASSET, currency);
        ledgerAccount.debit(new BigDecimal("10000.00"));

        return InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount);
    }
}
