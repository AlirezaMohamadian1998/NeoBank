package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.credit.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.debit.DebitCard;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.shared.MutableClock;
import com.neobank.neobank.shared.MutableClockConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(MutableClockConfiguration.class)
class CardTransactionsIntegrationTest extends CardIntegrationTestSupport {

    private static final String CARD_REFERENCE = "2".repeat(32);
    private static final String OTHER_CARD_REFERENCE = "3".repeat(32);
    private static final String MERCHANT_EMAIL = "merchant@example.com";
    private static final String MERCHANT_ACCOUNT_NUMBER = "98".repeat(7);

    @Autowired
    private MutableClock mutableClock;

    private Instant start;

    @BeforeEach
    void setUpCardTransactionsFixture() {
        start = Instant.now().minus(Duration.ofDays(1));
        mutableClock.setInstant(start);

        account.getLedgerAccount().credit(new BigDecimal("1000.00"));
        ledgerAccountRepository.save(account.getLedgerAccount());

        debitCardRepository.save(activeDebitCard(CARD_REFERENCE, "1234"));
        debitCardRepository.save(activeDebitCard(OTHER_CARD_REFERENCE, "5678"));

        Customer merchantCustomer = customerRepository.save(
                Customer.createNew(MERCHANT_EMAIL, "{bcrypt}password-hash", "Merchant Owner")
        );

        accountRepository.save(Account.createNew(
                MERCHANT_ACCOUNT_NUMBER,
                "5".repeat(32),
                "merchant",
                AccountType.CURRENT,
                merchantCustomer,
                CurrencyCode.TRY
        ));
    }

    @AfterEach
    void resetClock() {
        mutableClock.reset();
    }

    @Test
    void debitCardListsOnlyItsOwnPurchasesAndRefundsNewestFirst() throws Exception {
        String refundedPurchaseReference = purchase(CARD_REFERENCE, "100.00", "a".repeat(32), 1);
        purchase(OTHER_CARD_REFERENCE, "50.00", "b".repeat(32), 2);
        purchase(CARD_REFERENCE, "30.00", "c".repeat(32), 3);
        refund(refundedPurchaseReference, "d".repeat(32), 4);

        CreditCardIssueResponse creditCard = issueAndActivateCreditCard(5);
        purchase(creditCard.cardReference(), "20.00", "e".repeat(32), 6);
        repay(creditCard.cardReference(), "20.00", "f".repeat(32), 7);

        JsonNode transactions = transactions(CARD_REFERENCE, "");

        assertThat(transactions.size()).isEqualTo(3);

        assertThat(transactions.get(0).get("transactionType").asString()).isEqualTo("CARD_REFUND");
        assertThat(transactions.get(0).get("direction").asString()).isEqualTo("CREDIT");
        assertThat(transactions.get(0).get("purchaseReference").asString()).isEqualTo(refundedPurchaseReference);
        assertThat(transactions.get(0).get("cardReference").asString()).isEqualTo(CARD_REFERENCE);

        assertThat(transactions.get(1).get("transactionType").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(transactions.get(1).get("appliedAmount").decimalValue()).isEqualByComparingTo("30.00");

        assertThat(transactions.get(2).get("transactionType").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(transactions.get(2).get("direction").asString()).isEqualTo("DEBIT");
        assertThat(transactions.get(2).get("appliedAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(transactions.get(2).get("transactionReference").asString()).isEqualTo(refundedPurchaseReference);
        assertThat(transactions.get(2).get("merchantName").asString()).isEqualTo("Test merchant");
        assertThat(transactions.get(2).get("accountNumber").asString()).isEqualTo(account.getAccountNumber());
    }

    @Test
    void creditCardListsPurchasesAndRefundsButNotRepayments() throws Exception {
        CreditCardIssueResponse creditCard = issueAndActivateCreditCard(1);

        String refundedPurchaseReference = purchase(creditCard.cardReference(), "200.00", "a".repeat(32), 2);
        refund(refundedPurchaseReference, "b".repeat(32), 3);
        purchase(creditCard.cardReference(), "50.00", "c".repeat(32), 4);
        repay(creditCard.cardReference(), "50.00", "d".repeat(32), 5);

        JsonNode transactions = transactions(creditCard.cardReference(), "");

        assertThat(transactions.size()).isEqualTo(3);
        assertThat(transactions.get(0).get("transactionType").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(transactions.get(0).get("appliedAmount").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(transactions.get(0).get("accountNumber").asString()).isEqualTo(creditCard.creditAccountNumber());
        assertThat(transactions.get(1).get("transactionType").asString()).isEqualTo("CARD_REFUND");
        assertThat(transactions.get(1).get("purchaseReference").asString()).isEqualTo(refundedPurchaseReference);
        assertThat(transactions.get(2).get("transactionType").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(transactions.get(2).get("appliedAmount").decimalValue()).isEqualByComparingTo("200.00");
    }

    @Test
    void dateFilterLimitsTheTransactions() throws Exception {
        purchase(CARD_REFERENCE, "100.00", "a".repeat(32), 1);
        purchase(CARD_REFERENCE, "30.00", "b".repeat(32), 3);
        purchase(CARD_REFERENCE, "20.00", "c".repeat(32), 5);

        JsonNode transactions = transactions(
                CARD_REFERENCE,
                "?dateFrom=" + hoursAfterStart(2) + "&dateTo=" + hoursAfterStart(4)
        );

        assertThat(transactions.size()).isEqualTo(1);
        assertThat(transactions.get(0).get("appliedAmount").decimalValue()).isEqualByComparingTo("30.00");
    }

    @Test
    void customerCannotSeeAnotherCustomersCardTransactions() throws Exception {
        mockMvc.perform(get("/api/cards/{cardReference}/transactions", CARD_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(MERCHANT_EMAIL))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Card not found"));
    }

    private DebitCard activeDebitCard(String cardReference, String lastFourDigits) {
        DebitCard debitCard = DebitCard.createNew(
                cardReference,
                lastFourDigits,
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                account,
                TestCardLimits.generous()
        );
        debitCard.activateCard(YearMonth.now(clock));

        return debitCard;
    }

    private Instant hoursAfterStart(int hours) {
        return start.plus(Duration.ofHours(hours));
    }

    private JsonNode transactions(String cardReference, String query) throws Exception {
        String body = mockMvc.perform(get("/api/cards/" + cardReference + "/transactions" + query)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(body).get("content");
    }

    private String purchase(String cardReference, String amount, String idempotencyKey, int hoursAfterStart) throws Exception {
        mutableClock.setInstant(hoursAfterStart(hoursAfterStart));

        return transactionReferenceOf(purchase(cardReference, MERCHANT_ACCOUNT_NUMBER, amount, CurrencyCode.TRY, idempotencyKey));
    }

    private void refund(String purchaseReference, String idempotencyKey, int hoursAfterStart) throws Exception {
        mutableClock.setInstant(hoursAfterStart(hoursAfterStart));

        refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, idempotencyKey, MERCHANT_EMAIL)
                .andExpect(status().isCreated());
    }

    private CreditCardIssueResponse issueAndActivateCreditCard(int hoursAfterStart) throws Exception {
        mutableClock.setInstant(hoursAfterStart(hoursAfterStart));

        return issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));
    }

    private void repay(String cardReference, String amount, String idempotencyKey, int hoursAfterStart) throws Exception {
        mutableClock.setInstant(hoursAfterStart(hoursAfterStart));

        repay(cardReference, amount, null, idempotencyKey).andExpect(status().isCreated());
    }
}
