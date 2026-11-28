package com.neobank.neobank.card.limit;

import com.neobank.neobank.card.debit.DebitCard;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.ProviderRateCache;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CardSpendingLimitIntegrationTest extends CardIntegrationTestSupport {

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

        debitCard = savedActiveDebitCard(CARD_REFERENCE, CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00")));
    }

    @Test
    void purchaseOverPerTransactionLimitIsRejectedWithoutPersistingAnything() throws Exception {
        purchase(CARD_REFERENCE, null, "100.01", CurrencyCode.TRY, "1".repeat(32))
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
        purchase(CARD_REFERENCE, null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());
        purchase(CARD_REFERENCE, null, "100.00", CurrencyCode.TRY, "3".repeat(32)).andExpect(status().isCreated());

        purchase(CARD_REFERENCE, null, "50.01", CurrencyCode.TRY, "4".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Card limit exceeded"))
                .andExpect(jsonPath("$.detail").value("Purchase exceeds the card's daily limit"));

        assertThat(spentToday()).isEqualByComparingTo("200.00");

        purchase(CARD_REFERENCE, null, "50.00", CurrencyCode.TRY, "5".repeat(32)).andExpect(status().isCreated());

        assertThat(spentToday()).isEqualByComparingTo("250.00");
        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("9750.00");
        assertThat(cardDailySpendRepository.count()).isOne();
    }

    @Test
    void replayedPurchaseIsNotCountedTwice() throws Exception {
        purchase(CARD_REFERENCE, null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());
        purchase(CARD_REFERENCE, null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());

        assertThat(spentToday()).isEqualByComparingTo("100.00");
    }

    @Test
    void crossCurrencyPurchaseIsCountedInFundingCurrency() throws Exception {
        given(providerRateCache.getLatestRates()).willReturn(usdBasedRates("40.00"));

        purchase(CARD_REFERENCE, null, "2.00", CurrencyCode.USD, "1".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitedAmount").value(80.00));

        assertThat(spentToday()).isEqualByComparingTo("80.00");

        purchase(CARD_REFERENCE, null, "3.00", CurrencyCode.USD, "3".repeat(32))
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

        purchase(CARD_REFERENCE, null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());

        assertThat(spentToday()).isEqualByComparingTo("100.00");
        assertThat(cardDailySpendRepository.count()).isEqualTo(2);
    }

    @Test
    void refundDoesNotGiveBackDailyLimit() throws Exception {
        savedMerchantAccount("merchant@example.com", "98".repeat(7), "3".repeat(32), CurrencyCode.TRY);

        String body = purchase(CARD_REFERENCE, "98".repeat(7), "100.00", CurrencyCode.TRY, "1".repeat(32))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String purchaseReference = objectMapper.readValue(body, CardPurchaseResponse.class).transactionReference();

        refund("98".repeat(7), purchaseReference, "a".repeat(32), "merchant@example.com")
                .andExpect(status().isCreated());

        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("10000.00");
        assertThat(spentToday()).isEqualByComparingTo("100.00");
    }

    @Test
    void cardResponsesShowLimitsAndTodaysSpending() throws Exception {
        purchase(CARD_REFERENCE, null, "40.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/debit-cards/{cardReference}", CARD_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spendingLimits.perTransactionLimit").value(100.00))
                .andExpect(jsonPath("$.spendingLimits.dailyLimit").value(250.00))
                .andExpect(jsonPath("$.spendingLimits.spentToday").value(40.00))
                .andExpect(jsonPath("$.spendingLimits.currency").value("TRY"));

        mockMvc.perform(get("/api/debit-cards")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].spendingLimits.spentToday").value(40.00));
    }

    @Test
    void cardWithoutPurchasesTodayShowsZeroSpent() throws Exception {
        mockMvc.perform(get("/api/debit-cards/{cardReference}", CARD_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spendingLimits.spentToday").value(0.00));
    }

    @Test
    void customerCanRaiseLimitsAndThenSpendMore() throws Exception {
        updateLimits(CARD_REFERENCE, "500.00", "1000.00", customer.getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spendingLimits.perTransactionLimit").value(500.00))
                .andExpect(jsonPath("$.spendingLimits.dailyLimit").value(1000.00));

        purchase(CARD_REFERENCE, null, "300.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());

        assertThat(spentToday()).isEqualByComparingTo("300.00");
    }

    @Test
    void loweringDailyLimitBelowTodaysSpendingBlocksFurtherPurchases() throws Exception {
        purchase(CARD_REFERENCE, null, "100.00", CurrencyCode.TRY, "1".repeat(32)).andExpect(status().isCreated());

        updateLimits(CARD_REFERENCE, "50.00", "80.00", customer.getEmail()).andExpect(status().isOk());

        purchase(CARD_REFERENCE, null, "1.00", CurrencyCode.TRY, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Purchase exceeds the card's daily limit"));
    }

    @Test
    void limitsAboveBankMaximumAreRejected() throws Exception {
        updateLimits(CARD_REFERENCE, "10000000.01", "20000000.00", customer.getEmail())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid card limit"))
                .andExpect(jsonPath("$.detail").value("Per-transaction limit must not exceed 10000000.00"));

        assertThat(reloadedLimits())
                .isEqualTo(CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00")));
    }

    @Test
    void perTransactionLimitAboveDailyLimitIsRejected() throws Exception {
        updateLimits(CARD_REFERENCE, "300.00", "200.00", customer.getEmail())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Per-transaction limit must not exceed the daily limit"));
    }

    @Test
    void customerCannotChangeAnotherCustomersCardLimits() throws Exception {
        customerRepository.save(Customer.createNew("other@example.com", "{bcrypt}password-hash", "Other Customer"));

        updateLimits(CARD_REFERENCE, "50.00", "80.00", "other@example.com")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Debit card not found"));

        assertThat(reloadedLimits())
                .isEqualTo(CardSpendingLimits.createNew(new BigDecimal("100.00"), new BigDecimal("250.00")));
    }

    @Test
    void closedCardLimitsCannotBeChanged() throws Exception {
        debitCard.closeCard();
        debitCardRepository.save(debitCard);

        updateLimits(CARD_REFERENCE, "50.00", "80.00", customer.getEmail())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Card limits not changeable"));
    }

    private ResultActions updateLimits(String cardReference, String perTransaction, String daily, String email) throws Exception {
        return mockMvc.perform(put("/api/debit-cards/{cardReference}/spending-limits", cardReference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CardSpendingLimitsUpdateRequest(new BigDecimal(perTransaction), new BigDecimal(daily))
                ))
                .with(jwt().jwt(jwt -> jwt.subject(email))));
    }

    private CardSpendingLimits reloadedLimits() {
        return debitCardRepository.findByCardReference(CARD_REFERENCE).orElseThrow().getSpendingLimits();
    }

    private BigDecimal spentToday() {
        return cardDailySpendRepository.findByCardAndSpendDate(debitCard, LocalDate.now(clock))
                .orElseThrow()
                .getAmountSpent();
    }

}
