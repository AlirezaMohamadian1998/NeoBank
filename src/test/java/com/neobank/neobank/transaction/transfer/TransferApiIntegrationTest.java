package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.dto.FxRateLockResponse;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.CurrencyContext;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.FxInfo;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionApiIntegrationTestSupport;
import com.neobank.neobank.transaction.TransactionStatus;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TransferApiIntegrationTest extends TransactionApiIntegrationTestSupport {

    @Test
    void authenticatedCustomerCanTransferFromOwnedAccountToExistingAccount() throws Exception {
        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        Account targetAccount = Account.createNew(
                "98765432100123",
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                CurrencyCode.TRY
        );

        accountRepository.save(targetAccount);
        creditAndSave(sourceAccount, new BigDecimal("1000.00"));

        TransferRequest request = new TransferRequest(
                new BigDecimal("500"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        MvcResult transferMvcResult = transfer(sourceAccount.getAccountNumber(), request, IDEMPOTENCY_KEY, sourceCustomer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.sourceEntryReference").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.transactionType").value(TransactionType.TRANSFER.name()))
                .andExpect(jsonPath("$.sourceAccountNumber").value(sourceAccount.getAccountNumber()))
                .andExpect(jsonPath("$.destinationAccountNumber").value(targetAccount.getAccountNumber()))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.sourceBalanceAfter").value(500.00))
                .andExpect(jsonPath("$.currency").value(CurrencyCode.TRY.name()))
                .andExpect(jsonPath("$.note").value(request.note()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.entries").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.destinationEntryReference").doesNotHaveJsonPath())
                .andReturn();

        TransferResponse response = objectMapper.readValue(transferMvcResult.getResponse().getContentAsString(), TransferResponse.class);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);
        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);
        assertThat(accountRepository.count())
                .isEqualTo(2);
        assertThat(customerRepository.count())
                .isEqualTo(2);

        BankTransaction transaction = bankTransactionRepository.findAll().getFirst();

        assertThat(transaction.getReference())
                .isEqualTo(response.transactionReference());
        assertThat(transaction.getTransactionType())
                .isEqualTo(TransactionType.TRANSFER);
        assertThat(transaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);
        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(transaction.getRequestedCurrency())
                .isSameAs(request.currency());
        assertThat(transaction.getNote())
                .isEqualTo(request.note());
        assertThat(transaction.getCreatedAt())
                .isNotNull();
        assertThat(transaction.getId())
                .isNotNull();
        assertThat(transaction.getUpdatedAt())
                .isNotNull();

        Account savedSourceAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                        sourceAccount.getAccountNumber(),
                        sourceCustomer.getEmail())
                .orElseThrow(() -> new AccountNotFoundException());

        Account savedTargetAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                        targetAccount.getAccountNumber(),
                        targetCustomer.getEmail())
                .orElseThrow(() -> new AccountNotFoundException());

        List<LedgerEntry> entries = ledgerEntryRepository.findAll();
        var sourceEntry = entries.stream()
                .filter(entry -> entry.getLedgerAccount().getId().equals(savedSourceAccount.getLedgerAccount().getId()))
                .findFirst()
                .orElseThrow();

        var targetEntry = entries.stream()
                .filter(entry -> entry.getLedgerAccount().getId().equals(savedTargetAccount.getLedgerAccount().getId()))
                .findFirst()
                .orElseThrow();

        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(sourceEntry.getReference())
                .isEqualTo(response.sourceEntryReference());
        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(savedSourceAccount.getBalance());
        assertThat(sourceEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);
        assertThat(sourceEntry.getCurrency())
                .isSameAs(savedSourceAccount.getCurrency());
        assertThat(sourceEntry.getLedgerAccount().getId())
                .isEqualTo(savedSourceAccount.getLedgerAccount().getId());
        assertThat(sourceEntry.getBankTransaction().getId())
                .isEqualTo(transaction.getId());
        assertThat(sourceEntry.getCreatedAt())
                .isNotNull();
        assertThat(sourceEntry.getId())
                .isNotNull();
        assertThat(sourceEntry.getUpdatedAt())
                .isNotNull();

        assertThat(targetEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(targetEntry.getBalanceAfter())
                .isEqualByComparingTo(savedTargetAccount.getBalance());
        assertThat(targetEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(targetEntry.getCurrency())
                .isSameAs(savedTargetAccount.getCurrency());
        assertThat(targetEntry.getLedgerAccount().getId())
                .isEqualTo(savedTargetAccount.getLedgerAccount().getId());
        assertThat(targetEntry.getBankTransaction().getId())
                .isEqualTo(transaction.getId());
        assertThat(targetEntry.getCreatedAt())
                .isNotNull();
        assertThat(targetEntry.getId())
                .isNotNull();
        assertThat(targetEntry.getUpdatedAt())
                .isNotNull();
        assertThat(response.transactionType())
                .isEqualTo(transaction.getTransactionType());
        assertThat(response.createdAt())
                .isNotNull();

        assertThat(savedSourceAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(savedTargetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("500.00"));

        IdempotencyRecord idempotencyRecord = idempotencyRecordRepository.findAll().getFirst();
        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(IDEMPOTENCY_KEY);
        assertThat(idempotencyRecord.getResultReference())
                .isEqualTo(transaction.getReference());
        assertThat(idempotencyRecord.getCustomer().getId())
                .isEqualTo(sourceCustomer.getId());
    }

    @Test
    void authenticatedCustomerCanTransferBetweenOwnedSourceAndDestinationInDifferentCurrencies() throws Exception {
        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        Account targetAccount = Account.createNew(
                "98765432100123",
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                CurrencyCode.EUR
        );

        accountRepository.save(targetAccount);
        creditAndSave(sourceAccount, new BigDecimal("25000.00"));

        assertThat(accountRepository.count())
                .isEqualTo(2);

        assertThat(customerRepository.count())
                .isEqualTo(2);

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

        FxRateLockResponse fxRateResponse = fxRateService.createRateLock(CurrencyCode.USD, sourceCustomer.getEmail());

        TransferRequest request = new TransferRequest(
                new BigDecimal("500"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.USD,
                fxRateResponse.lockId()
        );

        MvcResult mvcResult = transfer(sourceAccount.getAccountNumber(), request, IDEMPOTENCY_KEY, sourceCustomer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.sourceEntryReference").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.transactionType").isNotEmpty())
                .andExpect(jsonPath("$.sourceAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.destinationAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.amount").isNotEmpty())
                .andExpect(jsonPath("$.sourceBalanceAfter").isNotEmpty())
                .andExpect(jsonPath("$.currency").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.entries").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.destinationEntryReference").doesNotHaveJsonPath())
                .andReturn();

        assertThat(bankTransactionRepository.count())
                .isOne();

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        TransferResponse response = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), TransferResponse.class);

        BankTransaction transaction = bankTransactionRepository.findAll().getFirst();

        List<LedgerEntry> entries = ledgerEntryRepository.findAll();

        LedgerEntry sourceEntry = entries.stream()
                .filter(entry -> entry.getDirection() == EntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry targetEntry = entries.stream()
                .filter(entry -> entry.getDirection() == EntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        Account savedSourceAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                        sourceAccount.getAccountNumber(),
                        sourceCustomer.getEmail())
                .orElseThrow(AccountNotFoundException::new);

        Account savedTargetAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                        targetAccount.getAccountNumber(),
                        targetCustomer.getEmail())
                .orElseThrow(AccountNotFoundException::new);

        assertThat(transaction.getTransactionType())
                .isEqualTo(TransactionType.TRANSFER);

        assertThat(transaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);

        assertThat(transaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());

        assertThat(transaction.getRequestedCurrency())
                .isSameAs(request.currency());

        assertThat(transaction.getNote())
                .isEqualTo(request.note());

        assertThat(transaction.getCreatedAt())
                .isNotNull();

        assertThat(transaction.getId())
                .isNotNull();

        assertThat(transaction.getUpdatedAt())
                .isNotNull();

        transactionTemplate.executeWithoutResult(status -> {
            FxInfo savedFxInfo = bankTransactionRepository.findById(transaction.getId())
                    .orElseThrow()
                    .getFxInfo();

            assertThat(savedFxInfo.getLockId())
                    .isEqualTo(fxRateResponse.lockId());

            assertThat(savedFxInfo.getRates().size())
                    .isEqualTo(3);

            assertThat(savedFxInfo.getRate(CurrencyContext.REQUEST))
                    .isEqualByComparingTo(fxRateResponse.rates().get(request.currency()));

            assertThat(savedFxInfo.getRate(CurrencyContext.SOURCE))
                    .isEqualByComparingTo(fxRateResponse.rates().get(sourceAccount.getCurrency()));

            assertThat(savedFxInfo.getRate(CurrencyContext.DESTINATION))
                    .isEqualByComparingTo(fxRateResponse.rates().get(targetAccount.getCurrency()));
        });

        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(
                        request.amount()
                                .multiply(fxRateResponse.rates().get(savedSourceAccount.getCurrency())).setScale(2, RoundingMode.HALF_EVEN)
                );

        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(savedSourceAccount.getBalance());

        assertThat(sourceEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(sourceEntry.getCurrency())
                .isSameAs(savedSourceAccount.getCurrency());

        assertThat(sourceEntry.getLedgerAccount().getId())
                .isEqualTo(savedSourceAccount.getLedgerAccount().getId());

        assertThat(sourceEntry.getBankTransaction().getId())
                .isEqualTo(transaction.getId());

        assertThat(sourceEntry.getCreatedAt())
                .isNotNull();

        assertThat(sourceEntry.getId())
                .isNotNull();

        assertThat(sourceEntry.getUpdatedAt())
                .isNotNull();

        assertThat(targetEntry.getAmount())
                .isEqualByComparingTo(
                        request.amount()
                                .multiply(fxRateResponse.rates().get(savedTargetAccount.getCurrency())).setScale(2, RoundingMode.HALF_EVEN)
                );

        assertThat(targetEntry.getBalanceAfter())
                .isEqualByComparingTo(savedTargetAccount.getBalance());

        assertThat(targetEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);

        assertThat(targetEntry.getCurrency())
                .isSameAs(savedTargetAccount.getCurrency());

        assertThat(targetEntry.getLedgerAccount().getId())
                .isEqualTo(savedTargetAccount.getLedgerAccount().getId());

        assertThat(targetEntry.getBankTransaction().getId())
                .isEqualTo(transaction.getId());

        assertThat(targetEntry.getCreatedAt())
                .isNotNull();

        assertThat(targetEntry.getId())
                .isNotNull();

        assertThat(targetEntry.getUpdatedAt())
                .isNotNull();

        assertThat(response.transactionReference())
                .isEqualTo(transaction.getReference());

        assertThat(response.sourceEntryReference())
                .isEqualTo(sourceEntry.getReference());

        assertThat(response.transactionType())
                .isEqualTo(transaction.getTransactionType());

        assertThat(response.sourceAccountNumber())
                .isEqualTo(savedSourceAccount.getAccountNumber());

        assertThat(response.destinationAccountNumber())
                .isEqualTo(savedTargetAccount.getAccountNumber());

        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());

        assertThat(response.sourceBalanceAfter())
                .isEqualByComparingTo(savedSourceAccount.getBalance());

        assertThat(response.currency())
                .isSameAs(request.currency());

        assertThat(response.note())
                .isEqualTo(request.note());

        assertThat(response.createdAt())
                .isNotNull();

        assertThat(savedSourceAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("5000.00"));

        assertThat(savedTargetAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("425.00"));
    }

    @Test
    void customerCannotTransferFromAnotherCustomersAccount() throws Exception {
        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        Account targetAccount = Account.createNew(
                "98765432100123",
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                CurrencyCode.TRY
        );

        accountRepository.save(targetAccount);
        creditAndSave(targetAccount, new BigDecimal("1000.00"));

        TransferRequest request = new TransferRequest(
                new BigDecimal("500"),
                sourceAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        transfer(targetAccount.getAccountNumber(), request, IDEMPOTENCY_KEY, sourceCustomer.getEmail())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/%s/transfers".formatted(targetAccount.getAccountNumber())))
                .andExpect(jsonPath("$.status").value(404));

        getAccount(sourceAccount.getAccountNumber(), sourceCustomer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(0.00));

        getAccount(targetAccount.getAccountNumber(), targetCustomer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(1000.00));

        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
        assertThat(ledgerEntryRepository.count())
                .isEqualTo(0);
    }

    @Test
    void customerCannotTransferMoreThanOnceWithSameIdempotencyKeyAndSameRequest() throws Exception {
        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        Account targetAccount = Account.createNew(
                "98765432100123",
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                CurrencyCode.TRY
        );

        accountRepository.save(targetAccount);
        creditAndSave(sourceAccount, new BigDecimal("1000.00"));

        TransferRequest request = new TransferRequest(
                new BigDecimal("500"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        String firstResponse = transfer(sourceAccount.getAccountNumber(), request, IDEMPOTENCY_KEY, sourceCustomer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        String secondResponse = transfer(sourceAccount.getAccountNumber(), request, IDEMPOTENCY_KEY, sourceCustomer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        getAccount(sourceAccount.getAccountNumber(), sourceCustomer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(500.00));

        getAccount(targetAccount.getAccountNumber(), targetCustomer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(500.00));

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
    void customerCannotUseTheSameIdempotencyKeyForDifferentTransferRequests() throws Exception {
        Customer sourceCustomer = customer;

        Customer targetCustomer = Customer.createNew(
                "target@example.com",
                "{bcrypt}raw-password321",
                "Lovelace Ada"
        );

        customerRepository.save(targetCustomer);

        Account sourceAccount = account;

        Account targetAccount = Account.createNew(
                "98765432100123",
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                "Target Account",
                AccountType.CURRENT,
                targetCustomer,
                CurrencyCode.TRY
        );

        accountRepository.save(targetAccount);
        creditAndSave(sourceAccount, new BigDecimal("1000.00"));

        TransferRequest request1 = new TransferRequest(
                new BigDecimal("500"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );

        TransferRequest request2 = new TransferRequest(
                new BigDecimal("250"),
                targetAccount.getAccountNumber(),
                "test",
                CurrencyCode.TRY,
                null
        );
        transfer(sourceAccount.getAccountNumber(), request1, IDEMPOTENCY_KEY, sourceCustomer.getEmail())
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sourceBalanceAfter").value(500.00));

        transfer(sourceAccount.getAccountNumber(), request2, IDEMPOTENCY_KEY, sourceCustomer.getEmail())
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("request hash mismatch"))
                .andExpect(jsonPath("$.title").value("Idempotency Conflict"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/%s/transfers".formatted(account.getAccountNumber())))
                .andExpect(jsonPath("$.status").value(409));

        getAccount(sourceAccount.getAccountNumber(), sourceCustomer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(500.00));

        getAccount(targetAccount.getAccountNumber(), targetCustomer.getEmail())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(500.00));

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(2);

        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);

        assertThat(idempotencyRecordRepository.count())
                .isEqualTo(1);
    }
}
