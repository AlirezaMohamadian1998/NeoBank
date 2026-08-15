package com.neobank.neobank.transaction;

import com.neobank.neobank.account.*;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
import org.junit.jupiter.api.BeforeEach;
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

class TransactionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private AccountEntryRepository accountEntryRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    private Account account;
    private Customer customer;

    @BeforeEach
    void setUp() {
        accountEntryRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        customer = customerRepository.save(Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
                )
        );

        account = accountRepository.save(Account.createNew(
                "12345678900987",
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY,
                customer
                )
        );
    }

    @Test
    void authenticatedCustomerCanDepositIntoOwnedAccount() throws Exception {
        DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test");

        MvcResult depositMvcResult = mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
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
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
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

        assertThat(accountEntryRepository.count())
                .isEqualTo(1);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);

        BankTransaction transaction = bankTransactionRepository.findAll().getFirst();
        AccountEntry entry = accountEntryRepository.findAll().getFirst();

        assertThat(transaction.getTransactionType())
                .isSameAs(TransactionType.DEPOSIT);
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

        assertThat(entry.getEntryDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(entry.getAmount())
                .isEqualByComparingTo(response.amount());
        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(savedAccount.getBalance());
        assertThat(entry.getAccount().getId())
                .isEqualTo(savedAccount.getId());
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

        DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test");

        mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
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

        assertThat(accountEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
    }

    @Test
    void invalidDepositRequestDoesNotChangeFinancialState() throws Exception {
        DepositRequest request = new DepositRequest(new BigDecimal("-1000.00"), "Test".repeat(100));

        mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/deposits"))
                .andExpect(jsonPath("$.errors.amount").value("Amount must be a positive number"))
                .andExpect(jsonPath("$.errors.note").value("Note must not exceed 255 characters"));

        mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(0.00));

        assertThat(accountEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
    }

    @Test
    void unauthenticatedDepositRequestDoesNotChangeFinancialState() throws Exception {
        DepositRequest request = new DepositRequest(new BigDecimal("1000.00"), "Test");

        mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(0.00));

        assertThat(accountEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
    }

    @Test
    void authenticatedCustomerCanWithdrawFromOwnedAccount() throws Exception {
        account.credit(new BigDecimal("1000.00"));
        accountRepository.save(account);

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("500.00"), "Test");

        MvcResult withdrawMvcResult = mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").value(matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.transactionType").value(TransactionType.WITHDRAWAL.name()))
                .andExpect(jsonPath("$.accountNumber").exists())
                .andExpect(jsonPath("$.amount").exists())
                .andExpect(jsonPath("$.currency").exists())
                .andExpect(jsonPath("$.balanceAfter").exists())
                .andExpect(jsonPath("$.note").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andReturn();

        mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500.00));

        WithdrawalResponse response = objectMapper.readValue(withdrawMvcResult.getResponse().getContentAsString(), WithdrawalResponse.class);

        assertThat(accountEntryRepository.count())
                .isEqualTo(1);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(1);

        Account savedAccount = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                        account.getAccountNumber(),
                        customer.getEmail())
                .orElseThrow(() -> new AccountNotFoundException());

        BankTransaction transaction = bankTransactionRepository.findAll().getFirst();
        AccountEntry entry = accountEntryRepository.findAll().getFirst();

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
        assertThat(entry.getBalanceAfter())
                .isEqualByComparingTo(savedAccount.getBalance());
        assertThat(entry.getAccount().getId())
                .isEqualTo(savedAccount.getId());
        assertThat(entry.getBankTransaction().getId())
                .isEqualTo(transaction.getId());
        assertThat(entry.getCurrency())
                .isSameAs(savedAccount.getCurrency());
        assertThat(entry.getEntryDirection())
                .isSameAs(EntryDirection.DEBIT);
        assertThat(entry.getCreatedAt())
                .isNotNull();
        assertThat(entry.getId())
                .isNotNull();
        assertThat(entry.getUpdatedAt())
                .isNotNull();
    }

    @Test
    void authenticatedCustomerCannotWithdrawFromOwnedAccountWithInsufficientBalance() throws Exception {
        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("500.00"), "Test");

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Insufficient funds"))
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/withdrawals"))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(0.00));

        assertThat(accountEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
    }

    @Test
    void unauthenticatedWithdrawalRequestDoesNotChangeFinancialState() throws Exception {
        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(0.00));

        assertThat(accountEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
    }

    @Test
    void invalidWithdrawalRequestDoesNotChangeFinancialState() throws Exception {
        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("-1000.00"), "Test".repeat(100));

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", account.getAccountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/withdrawals"))
                .andExpect(jsonPath("$.errors.amount").value("Amount must be a positive number"))
                .andExpect(jsonPath("$.errors.note").value("Note must not exceed 255 characters"));

        mockMvc.perform(get("/api/accounts/{accountNumber}", account.getAccountNumber())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.balance").value(0.00));

        assertThat(accountEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
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

        assertThat(accountEntryRepository.count())
                .isEqualTo(0);
        assertThat(bankTransactionRepository.count())
                .isEqualTo(0);
    }
}
