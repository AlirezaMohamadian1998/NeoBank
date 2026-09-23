package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyRecordRepository;
import com.neobank.neobank.internalaccount.InternalAccountRepository;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountRepository;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class DebitCardIssuanceIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private DebitCardRepository debitCardRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private InternalAccountRepository internalAccountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    private Customer customer;
    private Account account;

    @BeforeEach
    void setup() {
        idempotencyRecordRepository.deleteAll();
        debitCardRepository.deleteAll();
        accountRepository.deleteAll();
        internalAccountRepository.deleteAll();
        ledgerAccountRepository.deleteAll();
        customerRepository.deleteAll();

        customer = customerRepository.save(
                Customer.createNew(
                        "customer@example.com",
                        "{bcrypt}password-hash",
                        "Ada Lovelace"
                )
        );

        account = accountRepository.save(
                Account.createNew(
                        "12345678998745",
                        "test",
                        AccountType.CURRENT,
                        customer,
                        LedgerAccount.createNew(
                                "1".repeat(32),
                                LedgerAccountType.LIABILITY,
                                CurrencyCode.TRY
                        )
                )
        );
    }

    @Test
    void authenticatedCustomerCanIssueDebitCardForOwnedCurrentAccount() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";

        MvcResult mvcResult = mockMvc.perform(post("/api/debit-cards/{accountNumber}", account.getAccountNumber())
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").isNotEmpty())
                .andExpect(jsonPath("$.expirationYearMonth").isNotEmpty())
                .andExpect(jsonPath("$.fundingAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.lastFourDigits").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.fundingAccount").doesNotHaveJsonPath())
                .andReturn();

        DebitCardIssueResponse response = objectMapper.readValue(mvcResult.getResponse().getContentAsString(), DebitCardIssueResponse.class);

        assertThat(debitCardRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        transactionTemplate.executeWithoutResult(status -> {
            DebitCard debitCard = debitCardRepository.findByCardReference(response.cardReference())
                    .orElseThrow();

            IdempotencyRecord idempotencyRecord =
                    idempotencyRecordRepository.findByIdempotencyKeyAndCustomer_EmailIgnoreCase(idempotencyKey, customer.getEmail())
                            .orElseThrow();

            assertThat(response.lastFourDigits())
                    .isEqualTo(debitCard.getLastFourDigits());

            assertThat(response.fundingAccountNumber())
                    .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

            assertThat(response.expirationYearMonth())
                    .isEqualTo(debitCard.getExpirationYearMonth());

            assertThat(response.cardReference())
                    .isEqualTo(debitCard.getCardReference());

            assertThat(debitCard.getStatus())
                    .isSameAs(CardStatus.INACTIVE);

            assertThat(debitCard.getFundingAccount().getId())
                    .isEqualTo(account.getId());

            assertThat(debitCard.getCardReference())
                    .matches("[0-9a-f]{32}");

            assertThat(debitCard.getLastFourDigits())
                    .matches("\\d{4}");

            assertThat(debitCard.getId())
                    .isNotNull();

            assertThat(debitCard.getCreatedAt())
                    .isNotNull();

            assertThat(debitCard.getUpdatedAt())
                    .isNotNull();

            assertThat(debitCard.getExpirationYearMonth())
                    .isEqualTo(YearMonth.now(clock).plusYears(5));

            assertThat(idempotencyRecord.getResultReference())
                    .isEqualTo(debitCard.getCardReference());

            assertThat(idempotencyRecord.getCustomer().getId())
                    .isEqualTo(customer.getId());

            assertThat(idempotencyRecord.getIdempotencyKey())
                    .isEqualTo(idempotencyKey);

            assertThat(idempotencyRecord.getRequestHash())
                    .matches("[a-f0-9]{64}");

            assertThat(idempotencyRecord.getId())
                    .isNotNull();

            assertThat(idempotencyRecord.getCreatedAt())
                    .isNotNull();

            assertThat(idempotencyRecord.getUpdatedAt())
                    .isNotNull();
        });
    }

    @Test
    void customerCannotIssueDebitCardForAnotherCustomersAccount() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";

        Customer secondCustomer = customerRepository.save(
                Customer.createNew(
                        "customer2@example.com",
                        "{bcrypt}password",
                        "Lovelace Ada"
                )
        );

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", account.getAccountNumber())
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(secondCustomer.getEmail()))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(account.getAccountNumber())))
                .andExpect(jsonPath("$.status").value(404));

        assertThat(debitCardRepository.count())
                .isZero();

        assertThat(customerRepository.count())
                .isEqualTo(2);

        assertThat(idempotencyRecordRepository.count())
                .isZero();
    }

    @Test
    void repeatingDebitCardIssuanceWithSameKeyReturnsSameCardExactlyOnce() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";

        MvcResult firstMvcResult = mockMvc.perform(post("/api/debit-cards/{accountNumber}", account.getAccountNumber())
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").isNotEmpty())
                .andExpect(jsonPath("$.expirationYearMonth").isNotEmpty())
                .andExpect(jsonPath("$.fundingAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.lastFourDigits").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.fundingAccount").doesNotHaveJsonPath())
                .andReturn();

        DebitCardIssueResponse firstResponse =
                objectMapper.readValue(firstMvcResult.getResponse().getContentAsString(), DebitCardIssueResponse.class);

        assertThat(debitCardRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        MvcResult secondMvcResult = mockMvc.perform(post("/api/debit-cards/{accountNumber}", account.getAccountNumber())
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").isNotEmpty())
                .andExpect(jsonPath("$.expirationYearMonth").isNotEmpty())
                .andExpect(jsonPath("$.fundingAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.lastFourDigits").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.fundingAccount").doesNotHaveJsonPath())
                .andReturn();

        DebitCardIssueResponse secondResponse =
                objectMapper.readValue(secondMvcResult.getResponse().getContentAsString(), DebitCardIssueResponse.class);

        assertThat(debitCardRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        assertThat(firstResponse)
                .isEqualTo(secondResponse);
    }

    @Test
    void reusingIdempotencyKeyForDifferentAccountReturnsConflict() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";

        MvcResult result = mockMvc.perform(post("/api/debit-cards/{accountNumber}", account.getAccountNumber())
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        Account secondAccount = accountRepository.save(
                Account.createNew(
                        "98765432101234",
                        "test2",
                        AccountType.CURRENT,
                        customer,
                        LedgerAccount.createNew(
                                "2".repeat(32),
                                LedgerAccountType.LIABILITY,
                                CurrencyCode.USD
                        )
                )
        );

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", secondAccount.getAccountNumber())
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("request hash mismatch"))
                .andExpect(jsonPath("$.title").value("Idempotency Conflict"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(secondAccount.getAccountNumber())))
                .andExpect(jsonPath("$.status").value(409));

        DebitCardIssueResponse response =
                objectMapper.readValue(result.getResponse().getContentAsString(), DebitCardIssueResponse.class);

        assertThat(debitCardRepository.count())
                .isOne();

        assertThat(idempotencyRecordRepository.count())
                .isOne();

        transactionTemplate.executeWithoutResult(status -> {
            DebitCard debitCard = debitCardRepository.findByCardReference(response.cardReference())
                    .orElseThrow();

            IdempotencyRecord idempotencyRecord =
                    idempotencyRecordRepository.findByIdempotencyKeyAndCustomer_EmailIgnoreCase(idempotencyKey, customer.getEmail())
                            .orElseThrow();

            assertThat(debitCard.getFundingAccount().getAccountNumber())
                    .isEqualTo(account.getAccountNumber());

            assertThat(idempotencyRecord.getResultReference())
                    .isEqualTo(debitCard.getCardReference());
        });
    }
}
