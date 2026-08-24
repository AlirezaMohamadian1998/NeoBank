package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.AccountResponse;
import com.neobank.neobank.account.dto.CreateAccountRequest;
import com.neobank.neobank.auth.dto.LoginRequest;
import com.neobank.neobank.auth.dto.LoginResponse;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.customer.dto.RegisterCustomerRequest;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountStatus;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.LedgerEntryRepository;
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
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class AccountIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private BankTransactionRepository bankTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private InternalAccountRepository internalAccountRepository;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        bankTransactionRepository.deleteAll();
        accountRepository.deleteAll();
        internalAccountRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void authenticatedCustomerCanCreateAndRetrieveAccount() throws Exception {
        String email = "customer@example.com";
        String accessToken = registerAndLogin(email, "raw-password-123", "Ada Lovelace");

        CreateAccountRequest createAccountRequest = new CreateAccountRequest(
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        AccountResponse response = createAccount(accessToken, createAccountRequest);
        String accountNumber = response.accountNumber();

        assertThat(accountNumber)
                .matches("^[0-9]{14}$");
        assertThat(response.currency())
                .isSameAs(createAccountRequest.currency());

        assertThat(ledgerAccountRepository.count())
                .isEqualTo(1);

        LedgerAccount persistedLedgerAccount = ledgerAccountRepository.findAll().getFirst();

        assertThat(persistedLedgerAccount.getLedgerReference())
                .matches("[a-f0-9]{32}");
        assertThat(persistedLedgerAccount.getType())
                .isSameAs(LedgerAccountType.LIABILITY);
        assertThat(persistedLedgerAccount.getCurrency())
                .isSameAs(response.currency());
        assertThat(persistedLedgerAccount.getBalance())
                .isEqualByComparingTo(BigDecimal.ZERO.setScale(2));
        assertThat(persistedLedgerAccount.getStatus())
                .isSameAs(LedgerAccountStatus.ACTIVE);
        assertThat(persistedLedgerAccount.getVersion())
                .isNotNull();
        assertThat(persistedLedgerAccount.getId())
                .isNotNull();
        assertThat(persistedLedgerAccount.getCreatedAt())
                .isNotNull();
        assertThat(persistedLedgerAccount.getUpdatedAt())
                .isNotNull();

        Optional<Account> accountOptional = accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);

        assertThat(accountOptional)
                .isPresent();

        Account account = accountOptional.get();

        assertThat(account.getName())
                .isEqualTo(createAccountRequest.name());
        assertThat(account.getAccountType())
                .isSameAs(createAccountRequest.accountType());
        assertThat(account.getCurrency())
                .isSameAs(persistedLedgerAccount.getCurrency());
        assertThat(account.getBalance())
                .isEqualByComparingTo(persistedLedgerAccount.getBalance());
        assertThat(account.getLedgerAccount().getId())
                .isEqualTo(persistedLedgerAccount.getId());
        assertThat(account.getId())
                .isNotNull();
        assertThat(account.getCreatedAt())
                .isNotNull();
        assertThat(account.getUpdatedAt())
                .isNotNull();

        assertThat(customerRepository.count())
                .isEqualTo(1);
        assertThat(accountRepository.count())
                .isEqualTo(1);

        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.size()").value(1))
                .andExpect(jsonPath("$[0].accountNumber").value(accountNumber))
                .andExpect(jsonPath("$[0].name").value(response.name()))
                .andExpect(jsonPath("$[0].accountType").value(response.accountType().name()))
                .andExpect(jsonPath("$[0].currency").value(response.currency().name()))
                .andExpect(jsonPath("$[0].balance").value(0.00))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$.[*].id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.[*].version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.[*].ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.[*].ledgerAccountId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.[*].ledgerAccountReference").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.[*].customer").doesNotHaveJsonPath());

        mockMvc.perform(get("/api/accounts/{accountNumber}", accountNumber)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountNumber").value(accountNumber))
                .andExpect(jsonPath("$.name").value(response.name()))
                .andExpect(jsonPath("$.accountType").value(response.accountType().name()))
                .andExpect(jsonPath("$.currency").value(response.currency().name()))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccountId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccountReference").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath());
    }

    @Test
    void customerCannotRetrieveAnotherCustomersAccount() throws Exception {
        String customer1Email = "customer1@example.com";
        String customer1AccessToken = registerAndLogin(customer1Email, "raw-password-123", "Ada Lovelace");

        CreateAccountRequest customer1CreateAccountRequest = new CreateAccountRequest(
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        AccountResponse customer1AccountResponse = createAccount(customer1AccessToken, customer1CreateAccountRequest);
        String accountNumber = customer1AccountResponse.accountNumber();

        String customer2Email = "customer2@example.com";
        String customer2AccessToken = registerAndLogin(customer2Email, "raw-password-321", "Lovelace Ada");

        assertThat(customerRepository.count())
                .isEqualTo(2);
        assertThat(accountRepository.count())
                .isEqualTo(1);

        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + customer2AccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty())
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        mockMvc.perform(get("/api/accounts/{accountNumber}", accountNumber)
                        .header("Authorization", "Bearer " + customer2AccessToken))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/" + accountNumber))
                .andExpect(jsonPath("$.status").value(404));

        assertThat(customerRepository.count())
                .isEqualTo(2);
        assertThat(accountRepository.count())
                .isEqualTo(1);

        assertThat(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customer1Email))
                .isPresent();
        assertThat(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, customer2Email))
                .isEmpty();

        mockMvc.perform(get("/api/accounts/{accountNumber}", accountNumber)
                        .header("Authorization", "Bearer " + customer1AccessToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountNumber").value(accountNumber))
                .andExpect(jsonPath("$.name").value(customer1CreateAccountRequest.name()))
                .andExpect(jsonPath("$.accountType").value(customer1CreateAccountRequest.accountType().name()))
                .andExpect(jsonPath("$.currency").value(customer1CreateAccountRequest.currency().name()))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath());
    }

    private String registerAndLogin(String email, String password, String fullName) throws Exception {
        RegisterCustomerRequest registerRequest = new RegisterCustomerRequest(
                email,
                password,
                fullName
        );

        mockMvc.perform(post("/api/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.email").value(registerRequest.email()))
                .andExpect(jsonPath("$.fullName").value(registerRequest.fullName()))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.password").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.passwordHash").doesNotHaveJsonPath())
                .andExpect(content().string(not(containsString(registerRequest.password()))));

        LoginRequest loginRequest = new LoginRequest(
                registerRequest.email(),
                registerRequest.password()
        );

        MvcResult mvcLoginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(greaterThan(0)))
                .andExpect(jsonPath("$.password").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.passwordHash").doesNotHaveJsonPath())
                .andReturn();

        return objectMapper.readValue(mvcLoginResult.getResponse().getContentAsString(), LoginResponse.class).accessToken();
    }

    private AccountResponse createAccount(String accessToken, CreateAccountRequest createAccountRequest) throws Exception {
        MvcResult createAccountMvcResult =  mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createAccountRequest))
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountNumber").isNotEmpty())
                .andExpect(jsonPath("$.name").value(createAccountRequest.name()))
                .andExpect(jsonPath("$.accountType").value(createAccountRequest.accountType().name()))
                .andExpect(jsonPath("$.currency").value(createAccountRequest.currency().name()))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.ledgerAccount").doesNotHaveJsonPath())
                .andReturn();

        return objectMapper
                .readValue(createAccountMvcResult.getResponse().getContentAsString(), AccountResponse.class);
    }
}
