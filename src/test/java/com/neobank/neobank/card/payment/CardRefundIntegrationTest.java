package com.neobank.neobank.card.payment;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.debit.DebitCard;
import com.neobank.neobank.card.payment.dto.CardPurchaseRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.card.payment.dto.CardRefundResponse;
import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.ProviderRateCache;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.TransactionStatus;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CardRefundIntegrationTest extends DebitCardIntegrationTestSupport {

    private static final String CARD_REFERENCE = "2".repeat(32);
    private static final String MERCHANT_EMAIL = "merchant@example.com";
    private static final String MERCHANT_ACCOUNT_NUMBER = "98".repeat(7);
    private static final String MERCHANT_USD_ACCOUNT_NUMBER = "97".repeat(7);

    @MockitoBean
    private ProviderRateCache providerRateCache;

    @BeforeEach
    void setUpCardRefundFixture() {
        account.getLedgerAccount().credit(new BigDecimal("1000.00"));
        ledgerAccountRepository.save(account.getLedgerAccount());

        internalAccountRepository.save(createSettlementAccount("9".repeat(32), CurrencyCode.TRY));

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

        accountRepository.save(createAccount(MERCHANT_ACCOUNT_NUMBER, "5".repeat(32), CurrencyCode.TRY, merchantCustomer));
        accountRepository.save(createAccount(MERCHANT_USD_ACCOUNT_NUMBER, "6".repeat(32), CurrencyCode.USD, merchantCustomer));
    }

    @Test
    void merchantOwnerRefundsPurchaseAsNewTransactionAndMoneyReturnsToCard() throws Exception {
        String purchaseReference = purchase(MERCHANT_ACCOUNT_NUMBER, "250.00", CurrencyCode.TRY);

        CardRefundResponse response = readRefund(
                refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.purchaseReference").value(purchaseReference))
                        .andExpect(jsonPath("$.requestedAmount").value(250.00))
                        .andExpect(jsonPath("$.debitedAmount").value(250.00))
                        .andExpect(jsonPath("$.debitedCurrency").value("TRY"))
                        .andExpect(jsonPath("$.balanceAfter").value(0.00))
                        .andExpect(jsonPath("$.merchantName").value("Test merchant"))
                        .andExpect(jsonPath("$.merchantAccountNumber").value(MERCHANT_ACCOUNT_NUMBER))
                        .andExpect(jsonPath("$.cardLastFourDigits").value("1234"))
                        .andExpect(jsonPath("$.createdAt").isNotEmpty())
        );

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("1000.00");

        assertThat(balanceOf(MERCHANT_ACCOUNT_NUMBER))
                .isEqualByComparingTo("0.00");

        BankTransaction purchaseTransaction = bankTransactionRepository.findByReference(purchaseReference).orElseThrow();
        BankTransaction refundTransaction = bankTransactionRepository.findByReference(response.refundReference()).orElseThrow();

        assertThat(purchaseTransaction.getStatus())
                .isSameAs(TransactionStatus.REFUNDED);

        assertThat(purchaseTransaction.getEntries())
                .hasSize(2);

        assertThat(refundTransaction.getTransactionType())
                .isSameAs(TransactionType.CARD_REFUND);

        assertThat(refundTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(refundTransaction.getEntries())
                .hasSize(2);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(2);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(4);

        assertThat(cardRefundDetailsRepository.count())
                .isOne();
    }

    @Test
    void replayWithSameIdempotencyKeyReturnsSameRefundWithoutMovingMoneyAgain() throws Exception {
        String purchaseReference = purchase(MERCHANT_ACCOUNT_NUMBER, "250.00", CurrencyCode.TRY);

        CardRefundResponse first = readRefund(
                refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL)
                        .andExpect(status().isCreated())
        );

        CardRefundResponse replay = readRefund(
                refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL)
                        .andExpect(status().isCreated())
        );

        assertThat(replay.refundReference())
                .isEqualTo(first.refundReference());

        assertThat(replay.balanceAfter())
                .isEqualByComparingTo(first.balanceAfter());

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("1000.00");

        assertThat(cardRefundDetailsRepository.count())
                .isOne();

        assertThat(bankTransactionRepository.count())
                .isEqualTo(2);
    }

    @Test
    void secondRefundWithNewIdempotencyKeyIsRejected() throws Exception {
        String purchaseReference = purchase(MERCHANT_ACCOUNT_NUMBER, "250.00", CurrencyCode.TRY);

        refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL)
                .andExpect(status().isCreated());

        refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "b".repeat(32), MERCHANT_EMAIL)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card purchase not refundable"))
                .andExpect(jsonPath("$.detail").value("Card purchase is already refunded"));

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("1000.00");

        assertThat(cardRefundDetailsRepository.count())
                .isOne();
    }

    @Test
    void cardholderCannotRefundOwnPurchase() throws Exception {
        String purchaseReference = purchase(MERCHANT_ACCOUNT_NUMBER, "250.00", CurrencyCode.TRY);

        refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), customer.getEmail())
                .andExpect(status().isNotFound());

        refund(account.getAccountNumber(), purchaseReference, "b".repeat(32), customer.getEmail())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Card purchase not found"));

        assertThat(bankTransactionRepository.findByReference(purchaseReference).orElseThrow().getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("750.00");
    }

    @Test
    void purchaseAtOutsideMerchantCannotBeRefundedThroughThisEndpoint() throws Exception {
        String purchaseReference = purchase(null, "250.00", CurrencyCode.TRY);

        refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Card purchase not found"));

        assertThat(bankTransactionRepository.findByReference(purchaseReference).orElseThrow().getStatus())
                .isSameAs(TransactionStatus.COMPLETED);
    }

    @Test
    void unknownPurchaseReferenceReturnsNotFound() throws Exception {
        refund(MERCHANT_ACCOUNT_NUMBER, "f".repeat(32), "a".repeat(32), MERCHANT_EMAIL)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Card purchase not found"));
    }

    @Test
    void historyLetsMerchantFindPurchaseReferenceAndLinksRefundToPurchaseForCardholder() throws Exception {
        String purchaseReference = purchase(MERCHANT_ACCOUNT_NUMBER, "250.00", CurrencyCode.TRY);

        // The merchant finds the reference to refund in their own history.
        mockMvc.perform(get("/api/transactions")
                        .param("type", "CARD_PURCHASE")
                        .with(jwt().jwt(jwt -> jwt.subject(MERCHANT_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].accountNumber").value(MERCHANT_ACCOUNT_NUMBER))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].transactionReference").value(purchaseReference));

        CardRefundResponse refund = readRefund(
                refund(MERCHANT_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL)
                        .andExpect(status().isCreated())
        );

        // Cardholder: the purchase row and the refund row share the purchase reference.
        mockMvc.perform(get("/api/transactions")
                        .param("type", "CARD_PURCHASE")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].transactionReference").value(purchaseReference));

        mockMvc.perform(get("/api/transactions")
                        .param("type", "CARD_REFUND")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].accountNumber").value(account.getAccountNumber()))
                .andExpect(jsonPath("$.content[0].transactionReference").value(refund.refundReference()))
                .andExpect(jsonPath("$.content[0].transactionType").value("CARD_REFUND"))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].appliedAmount").value(250.00))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(1000.00))
                .andExpect(jsonPath("$.content[0].merchantName").value("Test merchant"))
                .andExpect(jsonPath("$.content[0].cardReference").value(CARD_REFERENCE))
                .andExpect(jsonPath("$.content[0].cardLastFourDigits").value("1234"))
                .andExpect(jsonPath("$.content[0].purchaseReference").value(purchaseReference));

        // Merchant: the refund is a plain debit row, mirroring how the purchase credit is shown.
        mockMvc.perform(get("/api/transactions")
                        .param("type", "CARD_REFUND")
                        .with(jwt().jwt(jwt -> jwt.subject(MERCHANT_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].accountNumber").value(MERCHANT_ACCOUNT_NUMBER))
                .andExpect(jsonPath("$.content[0].transactionReference").value(refund.refundReference()))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].appliedAmount").value(250.00))
                .andExpect(jsonPath("$.content[0].cardReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].purchaseReference").doesNotExist());
    }

    @Test
    void crossCurrencyRefundCreditsCardAtCurrentRate() throws Exception {
        stubUsdRates("40.00");

        // 10 USD at 40 TRY per USD debits 400 TRY from the card's account.
        String purchaseReference = purchase(MERCHANT_USD_ACCOUNT_NUMBER, "10.00", CurrencyCode.USD);

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("600.00");

        stubUsdRates("42.50");

        refund(MERCHANT_USD_ACCOUNT_NUMBER, purchaseReference, "a".repeat(32), MERCHANT_EMAIL)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedAmount").value(10.00))
                .andExpect(jsonPath("$.requestedCurrency").value("USD"))
                .andExpect(jsonPath("$.debitedAmount").value(10.00))
                .andExpect(jsonPath("$.debitedCurrency").value("USD"));

        assertThat(balanceOf(account.getAccountNumber()))
                .as("credited 425.00 TRY at the refund-time rate")
                .isEqualByComparingTo("1025.00");

        assertThat(balanceOf(MERCHANT_USD_ACCOUNT_NUMBER))
                .isEqualByComparingTo("0.00");
    }

    private String purchase(String merchantAccountNumber, String amount, CurrencyCode currency) throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                merchantAccountNumber,
                currency,
                new BigDecimal(amount),
                null,
                "Test merchant"
        );

        String body = mockMvc.perform(post("/api/cards/{cardReference}/payments", CARD_REFERENCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "c".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(body, CardPurchaseResponse.class).transactionReference();
    }

    private ResultActions refund(String merchantAccountNumber, String purchaseReference, String idempotencyKey, String email) throws Exception {
        return mockMvc.perform(post("/api/accounts/{merchantAccountNumber}/card-refunds/{purchaseReference}", merchantAccountNumber, purchaseReference)
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(email))));
    }

    private CardRefundResponse readRefund(ResultActions resultActions) throws Exception {
        return objectMapper.readValue(resultActions.andReturn().getResponse().getContentAsString(), CardRefundResponse.class);
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private void stubUsdRates(String tryPerUsd) {
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

    private Account createAccount(String accountNumber, String ledgerReference, CurrencyCode currency, Customer owner) {
        return Account.createNew(
                accountNumber,
                "merchant",
                AccountType.CURRENT,
                owner,
                LedgerAccount.createNew(ledgerReference, LedgerAccountType.LIABILITY, currency)
        );
    }

    private InternalAccount createSettlementAccount(String ledgerReference, CurrencyCode currency) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(ledgerReference, LedgerAccountType.ASSET, currency);
        ledgerAccount.debit(new BigDecimal("10000.00"));

        return InternalAccount.createNew(InternalAccountPurpose.SETTLEMENT, ledgerAccount);
    }
}
