package com.neobank.neobank.card.payment;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.card.debit.DebitCard;
import com.neobank.neobank.card.payment.dto.CardPurchaseRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import com.neobank.neobank.card.support.TestCardLimits;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.ProviderRateCache;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CardPaymentIntegrationTest extends CardIntegrationTestSupport {

    @MockitoBean
    private ProviderRateCache providerRateCache;

    private DebitCard debitCard;

    @BeforeEach
    void setUpCardPaymentFixture() {
        account.getLedgerAccount().credit(new BigDecimal("10000.00"));
        ledgerAccountRepository.save(account.getLedgerAccount());

        internalAccountRepository.save(createSettlementAccount("9".repeat(32), CurrencyCode.TRY));
        internalAccountRepository.save(createSettlementAccount("8".repeat(32), CurrencyCode.USD));

        DebitCard newDebitCard = DebitCard.createNew(
                "2".repeat(32),
                "1234",
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                account,
                TestCardLimits.generous()
        );
        newDebitCard.activateCard(YearMonth.now(clock));

        debitCard = debitCardRepository.save(newDebitCard);
    }

    @Test
    void customerCanPayExternalMerchantWithOwnedActiveCard() throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                "Coffee",
                "Test merchant"
        );

        MvcResult mvcResult = mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").isNotEmpty())
                .andExpect(jsonPath("$.requestedAmount").isNotEmpty())
                .andExpect(jsonPath("$.debitedAmount").isNotEmpty())
                .andExpect(jsonPath("$.requestedCurrency").isNotEmpty())
                .andExpect(jsonPath("$.debitedCurrency").isNotEmpty())
                .andExpect(jsonPath("$.balanceAfter").isNotEmpty())
                .andExpect(jsonPath("$.merchantName").isNotEmpty())
                .andExpect(jsonPath("$.cardLastFourDigits").isNotEmpty())
                .andExpect(jsonPath("$.cardReference").isNotEmpty())
                .andExpect(jsonPath("$.linkedAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.bankTransaction").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.fundingAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                .andReturn();

        CardPurchaseResponse response = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), CardPurchaseResponse.class);

        mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(9750.00));

        Account savedAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(account.getAccountNumber(), customer.getEmail())
                .orElseThrow();

        InternalAccount settlementAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY)
                .orElseThrow();

        assertThat(savedAccount.getBalance())
                .isEqualByComparingTo("9750.00");

        assertThat(settlementAccount.getBalance())
                .isEqualByComparingTo("9750.00");

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        assertThat(cardPurchaseDetailsRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        BankTransaction savedTransaction = bankTransactionRepository.findAll().getFirst();

        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());

        assertThat(response.requestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.debitedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.requestedCurrency())
                .isSameAs(CurrencyCode.TRY);

        assertThat(response.debitedCurrency())
                .isSameAs(CurrencyCode.TRY);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedAccount.getBalance());

        assertThat(response.merchantName())
                .isEqualTo(request.merchantName());

        assertThat(response.cardLastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(response.cardReference())
                .isEqualTo("2".repeat(32));

        assertThat(response.linkedAccountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());

        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.CARD_PURCHASE);

        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(savedTransaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedTransaction.getRequestedCurrency())
                .isSameAs(request.currency());

        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        transactionTemplate.executeWithoutResult(status -> {
            CardPurchaseDetails savedDetails = cardPurchaseDetailsRepository.findByBankTransaction_Reference(savedTransaction.getReference())
                    .orElseThrow();

            assertThat(savedDetails.getMerchantName())
                    .isEqualTo(request.merchantName());

            assertThat(savedDetails.getCardReference())
                    .isEqualTo("2".repeat(32));

            assertThat(savedDetails.getCardLastFourDigits())
                    .isEqualTo(debitCard.getLastFourDigits());

            assertThat(savedDetails.getBankTransaction().getId())
                    .isEqualTo(savedTransaction.getId());

            assertThat(savedDetails.getBankTransaction().getEntries())
                    .extracting(LedgerEntry::getDirection, entry -> entry.getLedgerAccount().getId())
                    .containsExactlyInAnyOrder(
                            tuple(EntryDirection.DEBIT, savedAccount.getLedgerAccount().getId()),
                            tuple(EntryDirection.CREDIT, settlementAccount.getLedgerAccount().getId())
                    );

            assertThat(bankTransactionRepository.findById(savedTransaction.getId()).orElseThrow().getFxInfo())
                    .isNull();
        });

        IdempotencyRecord idempotencyRecord = idempotencyRecordRepository.findAll().getFirst();

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo("1".repeat(32));

        assertThat(idempotencyRecord.getResultReference())
                .isEqualTo(savedTransaction.getReference());
    }

    @Test
    void customerCanPayMerchantAccountInAnotherCurrencyAndFxSnapshotIsPersisted() throws Exception {
        Customer merchantCustomer = customerRepository.save(
                Customer.createNew(
                        "merchant@example.com",
                        "{bcrypt}password-hash",
                        "Grace Hopper"
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
                                CurrencyCode.USD
                        )
                )
        );

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
                "98".repeat(7),
                CurrencyCode.USD,
                new BigDecimal("100.00"),
                "Test",
                "Test merchant"
        );

        MvcResult mvcResult = mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        CardPurchaseResponse response = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), CardPurchaseResponse.class);

        assertThat(response.requestedAmount())
                .isEqualByComparingTo("100.00");

        assertThat(response.requestedCurrency())
                .isSameAs(CurrencyCode.USD);

        assertThat(response.debitedAmount())
                .isEqualByComparingTo("4000.00");

        assertThat(response.debitedCurrency())
                .isSameAs(CurrencyCode.TRY);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo("6000.00");

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("6000.00");

        assertThat(accountRepository.findByAccountNumber(merchantAccount.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("100.00");

        assertThat(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY).orElseThrow().getBalance())
                .isEqualByComparingTo("10000.00");

        assertThat(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.USD).orElseThrow().getBalance())
                .isEqualByComparingTo("10000.00");

        transactionTemplate.executeWithoutResult(status -> {
            BankTransaction savedTransaction = bankTransactionRepository.findByReference(response.transactionReference())
                    .orElseThrow();

            FxInfo savedFxInfo = savedTransaction.getFxInfo();

            assertThat(savedFxInfo.getLockId())
                    .isNull();

            assertThat(savedFxInfo.getRates())
                    .extracting(FxRate::getCurrencyContext, FxRate::getCurrency)
                    .containsExactlyInAnyOrder(
                            tuple(CurrencyContext.REQUEST, CurrencyCode.USD),
                            tuple(CurrencyContext.SOURCE, CurrencyCode.TRY),
                            tuple(CurrencyContext.DESTINATION, CurrencyCode.USD)
                    );

            assertThat(savedFxInfo.getRate(CurrencyContext.REQUEST))
                    .isEqualByComparingTo(BigDecimal.ONE);

            assertThat(savedFxInfo.getRate(CurrencyContext.SOURCE))
                    .isEqualByComparingTo("40.00");

            assertThat(savedFxInfo.getRate(CurrencyContext.DESTINATION))
                    .isEqualByComparingTo(BigDecimal.ONE);

            assertThat(savedTransaction.getEntries())
                    .extracting(LedgerEntry::getDirection, LedgerEntry::getCurrency, LedgerEntry::getAmount)
                    .containsExactlyInAnyOrder(
                            tuple(EntryDirection.DEBIT, CurrencyCode.TRY, new BigDecimal("4000.00")),
                            tuple(EntryDirection.CREDIT, CurrencyCode.USD, new BigDecimal("100.00"))
                    );
        });
    }

    @Test
    void repeatedPurchaseWithSameIdempotencyKeyIsChargedOnlyOnce() throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                "Coffee",
                "Test merchant"
        );

        MvcResult firstMvcResult = mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult secondMvcResult = mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn();

        CardPurchaseResponse firstResponse = objectMapper.readValue(firstMvcResult.getResponse().getContentAsString(), CardPurchaseResponse.class);
        CardPurchaseResponse secondResponse = objectMapper.readValue(secondMvcResult.getResponse().getContentAsString(), CardPurchaseResponse.class);

        assertThat(secondResponse)
                .isEqualTo(firstResponse);

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("9750.00");

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        assertThat(cardPurchaseDetailsRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();
    }

    @Test
    void replayAfterCardIsFrozenReturnsOriginalPurchaseButNewPurchasesAreRejected() throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                "Coffee",
                "Test merchant"
        );

        MvcResult firstMvcResult = mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn();

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", "2".repeat(32), CardStatus.FROZEN.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(CardStatus.FROZEN.name()));

        MvcResult replayMvcResult = mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn();

        CardPurchaseResponse firstResponse = objectMapper.readValue(firstMvcResult.getResponse().getContentAsString(), CardPurchaseResponse.class);
        CardPurchaseResponse replayResponse = objectMapper.readValue(replayMvcResult.getResponse().getContentAsString(), CardPurchaseResponse.class);

        assertThat(replayResponse)
                .isEqualTo(firstResponse);

        mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "5".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card not active"))
                .andExpect(jsonPath("$.detail").value("Card must be active for purchase"));

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("9750.00");

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();
    }

    @Test
    void reusingIdempotencyKeyForDifferentPurchaseReturnsConflict() throws Exception {
        CardPurchaseRequest firstRequest = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                "Coffee",
                "Test merchant"
        );

        CardPurchaseRequest secondRequest = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("300.00"),
                "Coffee",
                "Test merchant"
        );

        mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(secondRequest))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Idempotency Conflict"));

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("9750.00");

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();
    }

    @Test
    void customerCannotPayWithAnotherCustomersCard() throws Exception {
        Customer otherCustomer = customerRepository.save(
                Customer.createNew(
                        "other@example.com",
                        "{bcrypt}password-hash",
                        "Grace Hopper"
                )
        );

        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                "Coffee",
                "Test merchant"
        );

        mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(otherCustomer.getEmail()))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card not found"));

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("10000.00");

        assertThat(bankTransactionRepository.count())
                .isZero();

        assertThat(idempotencyRecordRepository.count())
                .isZero();
    }

    @Test
    void inactiveCardCannotBeUsedForPurchase() throws Exception {
        String inactiveCardReference = "4".repeat(32);

        debitCardRepository.save(
                DebitCard.createNew(
                        inactiveCardReference,
                        "5678",
                        YearMonth.now(clock).plusYears(5),
                        YearMonth.now(clock),
                        account,
                        TestCardLimits.generous()
                )
        );

        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                "Coffee",
                "Test merchant"
        );

        mockMvc.perform(post("/api/cards/{cardReference}/payments", inactiveCardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card not active"));

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("10000.00");

        assertThat(bankTransactionRepository.count())
                .isZero();

        assertThat(idempotencyRecordRepository.count())
                .isZero();
    }

    @Test
    void purchaseExceedingBalanceIsRejectedWithoutPersistingAnything() throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("20000.00"),
                "Too expensive",
                "Test merchant"
        );

        mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Insufficient funds"));

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("10000.00");

        assertThat(internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, CurrencyCode.TRY).orElseThrow().getBalance())
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
    void purchaseAppearsWithCardDetailsOnlyInPayerHistory() throws Exception {
        Customer merchantCustomer = customerRepository.save(
                Customer.createNew(
                        "merchant@example.com",
                        "{bcrypt}password-hash",
                        "Merchant Owner"
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
                new BigDecimal("250.00"),
                "Coffee",
                "Test merchant"
        );

        mockMvc.perform(post("/api/cards/{cardReference}/payments", "2".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/transactions")
                        .param("type", "CARD_PURCHASE")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].accountNumber").value(account.getAccountNumber()))
                .andExpect(jsonPath("$.content[0].transactionType").value("CARD_PURCHASE"))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].appliedAmount").value(250.00))
                .andExpect(jsonPath("$.content[0].merchantName").value("Test merchant"))
                .andExpect(jsonPath("$.content[0].cardReference").value("2".repeat(32)))
                .andExpect(jsonPath("$.content[0].cardLastFourDigits").value(debitCard.getLastFourDigits()));

        mockMvc.perform(get("/api/transactions")
                        .param("type", "CARD_PURCHASE")
                        .with(jwt().jwt(jwt -> jwt.subject(merchantCustomer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].accountNumber").value(merchantAccount.getAccountNumber()))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].requestedAmount").value(250.00))
                .andExpect(jsonPath("$.content[0].merchantName").doesNotExist())
                .andExpect(jsonPath("$.content[0].cardReference").doesNotExist())
                .andExpect(jsonPath("$.content[0].cardLastFourDigits").doesNotExist());
    }
}
