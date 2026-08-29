package com.neobank.neobank.transaction;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class TransactionApiIntegrationTest extends TransactionIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    class DepositTests {
        @Test
        void authenticatedCustomerCanDepositIntoOwnedAccount() throws Exception {
            DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test", CurrencyCode.TRY, null);

            MvcResult depositMvcResult = mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
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

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
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

            mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer2.getEmail()))))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("Account not found"))
                    .andExpect(jsonPath("$.title").value("Account not found"))
                    .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/deposits"))
                    .andExpect(jsonPath("$.status").value(404));

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
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

            String firstResponse = mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();

            String secondResponse = mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
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

            mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request1))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.balanceAfter").value(1000.00));

            mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request2))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("request hash mismatch"))
                    .andExpect(jsonPath("$.title").value("Idempotency Conflict"))
                    .andExpect(jsonPath("$.instance").value("/api/accounts/%s/deposits".formatted(account.getAccountNumber())))
                    .andExpect(jsonPath("$.status").value(409));

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
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

    @Nested
    class WithdrawalTests {
        @Test
        void authenticatedCustomerCanWithdrawFromOwnedAccount() throws Exception {
            creditAndSave(account, new BigDecimal("1000.00"));

            WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("500.00"), "Test");

            MvcResult withdrawMvcResult = mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.transactionReference").value(matchesPattern("^[0-9a-f]{32}$")))
                    .andExpect(jsonPath("$.entryReference").value(matchesPattern("^[0-9a-f]{32}$")))
                    .andExpect(jsonPath("$.transactionType").value(TransactionType.WITHDRAWAL.name()))
                    .andExpect(jsonPath("$.accountNumber").value(account.getAccountNumber()))
                    .andExpect(jsonPath("$.amount").value(500.00))
                    .andExpect(jsonPath("$.currency").value(CurrencyCode.TRY.name()))
                    .andExpect(jsonPath("$.balanceAfter").value(500.00))
                    .andExpect(jsonPath("$.note").value(request.note()))
                    .andExpect(jsonPath("$.createdAt").exists())
                    .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.bankTransaction").doesNotHaveJsonPath())
                    .andReturn();

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(500.00));

            WithdrawalResponse response = objectMapper.readValue(withdrawMvcResult.getResponse().getContentAsString(), WithdrawalResponse.class);

            assertThat(ledgerEntryRepository.count())
                    .isEqualTo(2);

            assertThat(bankTransactionRepository.count())
                    .isEqualTo(1);

            Account savedAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                            account.getAccountNumber(),
                            customer.getEmail())
                    .orElseThrow(() -> new AccountNotFoundException());

            BankTransaction transaction = bankTransactionRepository.findAll().getFirst();
            var entry = ledgerEntryRepository
                    .findAll()
                    .stream()
                    .filter(e -> e.getDirection() == EntryDirection.DEBIT)
                    .findFirst()
                    .orElseThrow();

            assertThat(response.transactionReference())
                    .isEqualTo(transaction.getReference());
            assertThat(response.transactionType())
                    .isEqualTo(transaction.getTransactionType());
            assertThat(response.accountNumber())
                    .isEqualTo(account.getAccountNumber());
            assertThat(response.amount())
                    .isEqualByComparingTo(request.amount());
            assertThat(response.currency())
                    .isSameAs(account.getCurrency());
            assertThat(response.balanceAfter())
                    .isEqualByComparingTo(savedAccount.getBalance());
            assertThat(response.note())
                    .isEqualTo(request.note());
            assertThat(response.createdAt())
                    .isNotNull();

            assertThat(transaction.getTransactionType())
                    .isSameAs(TransactionType.WITHDRAWAL);
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

            assertThat(entry.getAmount())
                    .isEqualByComparingTo(response.amount());
            assertThat(entry.getReference())
                    .isEqualTo(response.entryReference());
            assertThat(entry.getBalanceAfter())
                    .isEqualByComparingTo(savedAccount.getBalance());
            assertThat(entry.getLedgerAccount().getId())
                    .isEqualTo(savedAccount.getLedgerAccount().getId());
            assertThat(entry.getBankTransaction().getId())
                    .isEqualTo(transaction.getId());
            assertThat(entry.getCurrency())
                    .isSameAs(savedAccount.getCurrency());
            assertThat(entry.getDirection())
                    .isSameAs(EntryDirection.DEBIT);
            assertThat(entry.getCreatedAt())
                    .isNotNull();
            assertThat(entry.getId())
                    .isNotNull();
            assertThat(entry.getUpdatedAt())
                    .isNotNull();
        }

        @Test
        void customerCannotWithdrawFromAnotherCustomersAccount() throws Exception {
            Customer customer2 = Customer.createNew(
                    "customer2@example.com",
                    "{bcrypt}password-encoded",
                    "Lovelace Ada"
            );
            customerRepository.save(customer2);

            WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

            mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer2.getEmail()))))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("Account not found"))
                    .andExpect(jsonPath("$.title").value("Account not found"))
                    .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/withdrawals"))
                    .andExpect(jsonPath("$.status").value(404));

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.balance").value(0.00));

            assertThat(ledgerEntryRepository.count())
                    .isEqualTo(0);
            assertThat(bankTransactionRepository.count())
                    .isEqualTo(0);
        }

        @Test
        void customerCannotWithdrawMoreThanOnceWithSameIdempotencyKeyAndSameRequest() throws Exception {
            creditAndSave(account, new BigDecimal("1000.00"));

            WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("500.00"), "Test");

            String firstResponse = mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();

            String secondResponse = mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
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
        void customerCannotUseTheSameIdempotencyKeyForDifferentWithdrawalRequests() throws Exception {
            creditAndSave(account, new BigDecimal("1000.00"));

            WithdrawalRequest request1 = new WithdrawalRequest(new BigDecimal("500.00"), "Test");
            WithdrawalRequest request2 = new WithdrawalRequest(new BigDecimal("250.00"), "Test");

            mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request1))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.balanceAfter").value(500.00));

            mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request2))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("request hash mismatch"))
                    .andExpect(jsonPath("$.title").value("Idempotency Conflict"))
                    .andExpect(jsonPath("$.instance").value("/api/accounts/%s/withdrawals".formatted(account.getAccountNumber())))
                    .andExpect(jsonPath("$.status").value(409));

            mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
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

    @Nested
    class TransferTests {
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

            LedgerAccount targetLedger = LedgerAccount.createNew(
                    "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                    LedgerAccountType.LIABILITY,
                    CurrencyCode.TRY
            );

            Account targetAccount = Account.createNew(
                    "98765432100123",
                    "Target Account",
                    AccountType.CURRENT,
                    targetCustomer,
                    targetLedger
            );

            accountRepository.save(targetAccount);
            creditAndSave(sourceAccount, new BigDecimal("1000.00"));

            TransferRequest request = new TransferRequest(
                    new BigDecimal("500"),
                    targetAccount.getAccountNumber(),
                    "test",
                    CurrencyCode.TRY
            );

            MvcResult transferMvcResult = mockMvc.perform(post("/api/accounts/{accountNumber}/transfers", sourceAccount.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
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

            assertThat(response.transactionReference())
                    .matches("^[0-9a-f]{32}$");
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
                    .isSameAs(savedSourceAccount.getCurrency());
            assertThat(response.note())
                    .isEqualTo(request.note());
            assertThat(response.createdAt())
                    .isNotNull();

            assertThat(savedSourceAccount.getBalance())
                    .isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(savedTargetAccount.getBalance())
                    .isEqualByComparingTo(new BigDecimal("500.00"));
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

            LedgerAccount targetLedger = LedgerAccount.createNew(
                    "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                    LedgerAccountType.LIABILITY,
                    CurrencyCode.TRY
            );

            Account targetAccount = Account.createNew(
                    "98765432100123",
                    "Target Account",
                    AccountType.CURRENT,
                    targetCustomer,
                    targetLedger
            );

            accountRepository.save(targetAccount);
            creditAndSave(targetAccount, new BigDecimal("1000.00"));

            TransferRequest request = new TransferRequest(
                    new BigDecimal("500"),
                    sourceAccount.getAccountNumber(),
                    "test",
                    CurrencyCode.TRY
            );

            mockMvc.perform(post("/api/accounts/{sourceAccountNumber}/transfers", targetAccount.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("Account not found"))
                    .andExpect(jsonPath("$.title").value("Account not found"))
                    .andExpect(jsonPath("$.instance").value("/api/accounts/%s/transfers".formatted(targetAccount.getAccountNumber())))
                    .andExpect(jsonPath("$.status").value(404));

            mockMvc.perform(get("/api/accounts/{accountNumber}", sourceAccount.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.balance").value(0.00));

            mockMvc.perform(get("/api/accounts/{accountNumber}", targetAccount.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(targetCustomer.getEmail()))))
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

            LedgerAccount targetLedger = LedgerAccount.createNew(
                    "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                    LedgerAccountType.LIABILITY,
                    CurrencyCode.TRY
            );

            Account targetAccount = Account.createNew(
                    "98765432100123",
                    "Target Account",
                    AccountType.CURRENT,
                    targetCustomer,
                    targetLedger
            );

            accountRepository.save(targetAccount);
            creditAndSave(sourceAccount, new BigDecimal("1000.00"));

            TransferRequest request = new TransferRequest(
                    new BigDecimal("500"),
                    targetAccount.getAccountNumber(),
                    "test",
                    CurrencyCode.TRY
            );

            String firstResponse = mockMvc.perform(post("/api/accounts/{accountNumber}/transfers", sourceAccount.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();

            String secondResponse = mockMvc.perform(post("/api/accounts/{accountNumber}/transfers", sourceAccount.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();

            mockMvc.perform(get("/api/accounts/{accountNumber}", sourceAccount.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.balance").value(500.00));

            mockMvc.perform(get("/api/accounts/{accountNumber}", targetAccount.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(targetCustomer.getEmail()))))
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

            LedgerAccount targetLedger = LedgerAccount.createNew(
                    "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                    LedgerAccountType.LIABILITY,
                    CurrencyCode.TRY
            );

            Account targetAccount = Account.createNew(
                    "98765432100123",
                    "Target Account",
                    AccountType.CURRENT,
                    targetCustomer,
                    targetLedger
            );

            accountRepository.save(targetAccount);
            creditAndSave(sourceAccount, new BigDecimal("1000.00"));

            TransferRequest request1 = new TransferRequest(
                    new BigDecimal("500"),
                    targetAccount.getAccountNumber(),
                    "test",
                    CurrencyCode.TRY
            );

            TransferRequest request2 = new TransferRequest(
                    new BigDecimal("250"),
                    targetAccount.getAccountNumber(),
                    "test",
                    CurrencyCode.TRY
            );
            mockMvc.perform(post("/api/accounts/{accountNumber}/transfers", sourceAccount.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request1))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.sourceBalanceAfter").value(500.00));

            mockMvc.perform(post("/api/accounts/{accountNumber}/transfers", sourceAccount.getAccountNumber())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request2))
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("request hash mismatch"))
                    .andExpect(jsonPath("$.title").value("Idempotency Conflict"))
                    .andExpect(jsonPath("$.instance").value("/api/accounts/%s/transfers".formatted(account.getAccountNumber())))
                    .andExpect(jsonPath("$.status").value(409));

            mockMvc.perform(get("/api/accounts/{accountNumber}", sourceAccount.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(sourceCustomer.getEmail()))))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.balance").value(500.00));

            mockMvc.perform(get("/api/accounts/{accountNumber}", targetAccount.getAccountNumber())
                            .with(jwt().jwt(jwt -> jwt.subject(targetCustomer.getEmail()))))
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

}
