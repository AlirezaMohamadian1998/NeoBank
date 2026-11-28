package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.CurrencyContext;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.FxInfo;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionApiIntegrationTestSupport;
import com.neobank.neobank.transaction.TransactionStatus;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DepositApiIntegrationTest extends TransactionApiIntegrationTestSupport {

    @Test
    void customerCanReuseFxLockForDistinctDeposits() throws Exception {
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

        FxRateLockResponse fxRateResponse = fxRateService.createRateLock(CurrencyCode.USD, customer.getEmail());

        DepositRequest firstRequest = new DepositRequest(
                new BigDecimal("100.00"),
                "First deposit",
                CurrencyCode.USD,
                fxRateResponse.lockId()
        );

        DepositRequest secondRequest = new DepositRequest(
                new BigDecimal("200.00"),
                "Second deposit",
                CurrencyCode.USD,
                fxRateResponse.lockId()
        );

        MvcResult firstMvcResult = deposit(account.getAccountNumber(), firstRequest, IDEMPOTENCY_KEY, customer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value(4000.00))
                .andReturn();

        MvcResult secondMvcResult = deposit(account.getAccountNumber(), secondRequest, "2".repeat(32), customer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value(8000.00))
                .andReturn();

        DepositResponse firstResponse = objectMapper.readValue(firstMvcResult.getResponse().getContentAsString(), DepositResponse.class);
        DepositResponse secondResponse = objectMapper.readValue(secondMvcResult.getResponse().getContentAsString(), DepositResponse.class);

        assertThat(firstResponse.transactionReference())
                .isNotEqualTo(secondResponse.transactionReference());

        assertThat(bankTransactionRepository.count())
                .isEqualTo(2);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(4);

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(2);

        getAccount(account.getAccountNumber(), customer.getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(12000.00));

        transactionTemplate.executeWithoutResult(status -> {
            BankTransaction firstTransaction = bankTransactionRepository.findByReference(firstResponse.transactionReference())
                    .orElseThrow();

            BankTransaction secondTransaction = bankTransactionRepository.findByReference(secondResponse.transactionReference())
                    .orElseThrow();

            FxInfo firstFxInfo = firstTransaction.getFxInfo();
            FxInfo secondFxInfo = secondTransaction.getFxInfo();

            assertThat(firstTransaction.getStatus())
                    .isSameAs(TransactionStatus.COMPLETED);

            assertThat(secondTransaction.getStatus())
                    .isSameAs(TransactionStatus.COMPLETED);

            assertThat(firstFxInfo.getId())
                    .isNotNull()
                    .isNotEqualTo(secondFxInfo.getId());

            assertThat(secondFxInfo.getId())
                    .isNotNull();

            assertThat(firstFxInfo.getLockId())
                    .isEqualTo(fxRateResponse.lockId());

            assertThat(secondFxInfo.getLockId())
                    .isEqualTo(fxRateResponse.lockId());

            assertThat(firstFxInfo.getRate(CurrencyContext.DESTINATION))
                    .isEqualByComparingTo(new BigDecimal("40.00"));

            assertThat(secondFxInfo.getRate(CurrencyContext.DESTINATION))
                    .isEqualByComparingTo(new BigDecimal("40.00"));
        });
    }

    @Test
    void authenticatedCustomerCanDepositIntoOwnedAccount() throws Exception {
        DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        MvcResult depositMvcResult = deposit(account.getAccountNumber(), request, IDEMPOTENCY_KEY, customer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value(1000.00))
                .andExpect(jsonPath("$.balanceAfter").value(1000.00))
                .andExpect(jsonPath("$.transactionType").value(TransactionType.DEPOSIT.name()))
                .andExpect(jsonPath("$.currency").value(CurrencyCode.TRY.name()))
                .andExpect(jsonPath("$.accountNumber").value(account.getAccountNumber()))
                .andExpect(jsonPath("$.note").value(request.note()))
                .andExpect(jsonPath("$.transactionReference").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.entryReference").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.bankTransaction").doesNotHaveJsonPath())
                .andReturn();

        DepositResponse response = objectMapper.readValue(depositMvcResult.getResponse().getContentAsString(), DepositResponse.class);

        getAccount(account.getAccountNumber(), customer.getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.00));

        Account savedAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                        account.getAccountNumber(),
                        customer.getEmail())
                .orElseThrow(() -> new AccountNotFoundException());

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);

        BankTransaction transaction = bankTransactionRepository.findAll().getFirst();
        var entry = ledgerEntryRepository
                .findAll()
                .stream()
                .filter(e -> e.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(transaction.getTransactionType())
                .isSameAs(TransactionType.DEPOSIT);
        assertThat(transaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);
        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(transaction.getRequestedCurrency())
                .isSameAs(savedAccount.getCurrency());
        assertThat(transaction.getNote())
                .isEqualTo(response.note());
        assertThat(transaction.getReference())
                .isEqualTo(response.transactionReference());
        assertThat(transaction.getCreatedAt())
                .isNotNull();
        assertThat(transaction.getId())
                .isNotNull();
        assertThat(transaction.getUpdatedAt())
                .isNotNull();

        assertThat(entry.getDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(entry.getReference())
                .isEqualTo(response.entryReference());
        assertThat(entry.getAmount())
                .isEqualByComparingTo(response.amount());
        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(response.balanceAfter());
        assertThat(savedAccount.getBalance())
                .isEqualByComparingTo(response.balanceAfter());
        assertThat(entry.getLedgerAccount().getId())
                .isEqualTo(savedAccount.getLedgerAccount().getId());
        assertThat(entry.getBankTransaction().getId())
                .isEqualTo(transaction.getId());
        assertThat(entry.getCurrency())
                .isSameAs(savedAccount.getCurrency());
        assertThat(entry.getCreatedAt())
                .isNotNull();
        assertThat(entry.getId())
                .isNotNull();
        assertThat(entry.getUpdatedAt())
                .isNotNull();

        IdempotencyRecord idempotencyRecord = idempotencyRecordRepository.findAll().getFirst();
        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(IDEMPOTENCY_KEY);
        assertThat(idempotencyRecord.getResultReference())
                .isEqualTo(transaction.getReference());
        assertThat(idempotencyRecord.getCustomer().getId())
                .isEqualTo(customer.getId());
    }

    @Test
    void authenticatedCustomerCanDepositDifferentCurrencyIntoOwnedAccount() throws Exception {
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

        FxRateLockResponse fxRateResponse = fxRateService.createRateLock(CurrencyCode.USD, customer.getEmail());

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test", CurrencyCode.USD,
                fxRateResponse.lockId()
        );

        MvcResult mvcResult = deposit(account.getAccountNumber(), request, IDEMPOTENCY_KEY, customer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").isNotEmpty())
                .andExpect(jsonPath("$.entryReference").isNotEmpty())
                .andExpect(jsonPath("$.transactionType").isNotEmpty())
                .andExpect(jsonPath("$.accountNumber").isNotEmpty())
                .andExpect(jsonPath("$.amount").isNotEmpty())
                .andExpect(jsonPath("$.currency").isNotEmpty())
                .andExpect(jsonPath("$.balanceAfter").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.bankTransaction").doesNotHaveJsonPath())
                .andReturn();

        getAccount(account.getAccountNumber(), customer.getEmail())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(40000.00));

        DepositResponse response = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), DepositResponse.class);

        Account savedAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(account.getAccountNumber(), customer.getEmail())
                .orElseThrow();

        InternalAccount internalAccount = internalAccountRepository.findByPurposeAndCurrency(InternalAccountPurpose.SETTLEMENT, request.requestedCurrency())
                .orElseThrow();

        assertThat(internalAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("11000"));

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        BankTransaction savedTransaction = bankTransactionRepository.findAll().getFirst();

        LedgerEntry savedEntry = ledgerEntryRepository
                .findAll()
                .stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());

        assertThat(response.entryReference())
                .isEqualTo(savedEntry.getReference());

        assertThat(response.transactionType())
                .isEqualTo(savedTransaction.getTransactionType());

        assertThat(response.accountNumber())
                .isEqualTo(savedAccount.getAccountNumber());

        assertThat(response.amount())
                .isEqualByComparingTo(savedEntry.getAmount());

        assertThat(response.currency())
                .isSameAs(savedEntry.getCurrency());

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedEntry.getBalanceAfter());

        assertThat(response.note())
                .isEqualTo(request.note());

        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());

        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.DEPOSIT);

        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(savedTransaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(savedTransaction.getRequestedCurrency())
                .isSameAs(request.requestedCurrency());

        assertThat(savedTransaction.getNote())
                .isEqualTo(response.note());

        assertThat(savedTransaction.getId())
                .isNotNull();

        assertThat(savedTransaction.getUpdatedAt())
                .isNotNull();

        transactionTemplate.executeWithoutResult(status -> {
            FxInfo savedFxInfo = bankTransactionRepository.findById(savedTransaction.getId())
                    .orElseThrow()
                    .getFxInfo();

            assertThat(savedFxInfo.getLockId())
                    .isEqualTo(fxRateResponse.lockId());

            assertThat(savedFxInfo.getRates().size())
                    .isEqualTo(3);

            assertThat(savedFxInfo.getRate(CurrencyContext.REQUEST))
                    .isEqualByComparingTo(BigDecimal.ONE);

            assertThat(savedFxInfo.getRate(CurrencyContext.SOURCE))
                    .isEqualByComparingTo(BigDecimal.ONE);

            assertThat(savedFxInfo.getRate(CurrencyContext.DESTINATION))
                    .isEqualByComparingTo(new BigDecimal("40.00"));
        });

        assertThat(savedEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(savedEntry.getAmount())
                .isEqualByComparingTo(
                        request.amount()
                                .multiply(fxRateResponse.rates().get(account.getCurrency()))
                                .setScale(2, RoundingMode.HALF_EVEN)
                );

        assertThat(savedEntry.getBalanceAfter())
                .isEqualByComparingTo(savedAccount.getBalance());

        assertThat(savedEntry.getLedgerAccount().getId())
                .isEqualTo(savedAccount.getLedgerAccount().getId());

        assertThat(savedEntry.getBankTransaction().getId())
                .isEqualTo(savedTransaction.getId());

        assertThat(savedEntry.getCurrency())
                .isSameAs(savedAccount.getCurrency());

        assertThat(savedEntry.getCreatedAt())
                .isNotNull();

        assertThat(savedEntry.getId())
                .isNotNull();

        assertThat(savedEntry.getUpdatedAt())
                .isNotNull();
    }

    @Test
    void customerCannotDepositIntoAnotherCustomersAccount() throws Exception {
        Customer customer2 = Customer.createNew(
                "customer2@example.com",
                "{bcrypt}password-encoded",
                "Lovelace Ada"
        );

        customerRepository.save(customer2);

        DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        deposit(account.getAccountNumber(), request, IDEMPOTENCY_KEY, customer2.getEmail())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/deposits"))
                .andExpect(jsonPath("$.status").value(404));

        getAccount(account.getAccountNumber(), customer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(0.00));

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
    }

    @Test
    void customerCannotDepositMoreThanOnceWithSameIdempotencyKeyAndSameRequest() throws Exception {
        DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

        String firstResponse = deposit(account.getAccountNumber(), request, IDEMPOTENCY_KEY, customer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        String secondResponse = deposit(account.getAccountNumber(), request, IDEMPOTENCY_KEY, customer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        getAccount(account.getAccountNumber(), customer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(1000.00));

        assertThat(firstResponse)
                .isEqualTo(secondResponse);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(1);
    }

    @Test
    void customerCannotUseTheSameIdempotencyKeyForDifferentDepositsRequests() throws Exception {
        DepositRequest request1 = new DepositRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);
        DepositRequest request2 = new DepositRequest(new BigDecimal("2000.00"), "Test", CurrencyCode.TRY, null);

        deposit(account.getAccountNumber(), request1, IDEMPOTENCY_KEY, customer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balanceAfter").value(1000.00));

        deposit(account.getAccountNumber(), request2, IDEMPOTENCY_KEY, customer.getEmail())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("request hash mismatch"))
                .andExpect(jsonPath("$.title").value("Idempotency Conflict"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/%s/deposits".formatted(account.getAccountNumber())))
                .andExpect(jsonPath("$.status").value(409));

        getAccount(account.getAccountNumber(), customer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(1000.00));

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(1);
    }
}
