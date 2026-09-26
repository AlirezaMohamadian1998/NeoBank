package com.neobank.neobank.card;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
class DebitCardQueryIntegrationTest {
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DebitCardRepository debitCardRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private LedgerAccountRepository ledgerAccountRepository;

    @Autowired
    private InternalAccountRepository internalAccountRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Customer customer;
    private DebitCard debitCard;

    @Autowired
    private Clock clock;

    @BeforeEach
    void setup() {
        idempotencyRecordRepository.deleteAll();
        debitCardRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();
        internalAccountRepository.deleteAll();
        ledgerAccountRepository.deleteAll();

        customer = customerRepository.save(
                Customer.createNew(
                        "customer@example.com",
                        "{bcrypt}password-hash",
                        "Ada Lovelace"
                )
        );

        Account account = accountRepository.save(
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

        debitCard = DebitCard.createNew(
                "2".repeat(32),
                "1234",
                YearMonth.now(clock).plusYears(5),
                YearMonth.now(clock),
                account
        );

        debitCard.activateCard(YearMonth.now(clock));

        debitCard = debitCardRepository.save(debitCard);
    }

    @Test
    void customerCanRetrieveOwnedDebitCard() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/debit-cards/{cardReference}", debitCard.getCardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").isNotEmpty())
                .andExpect(jsonPath("$.lastFourDigits").isNotEmpty())
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.expirationYearMonth").isNotEmpty())
                .andExpect(jsonPath("$.fundingAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.fundingAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andReturn();

        DebitCardRetrieveResponse response =
                objectMapper.readValue(result.getResponse().getContentAsString(), DebitCardRetrieveResponse.class);

        assertThat(response.cardReference())
                .isEqualTo(debitCard.getCardReference());

        assertThat(response.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(response.status())
                .isSameAs(debitCard.getStatus());

        assertThat(response.status())
                .isSameAs(CardStatus.ACTIVE);

        assertThat(response.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(response.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());
    }

    @Test
    void customerCannotRetrieveAnotherCustomersDebitCard() throws Exception {
        Customer secondCustomer = customerRepository.save(
                Customer.createNew(
                        "example@customer.com",
                        "{bcrypt}password-hash",
                        "Lovelace Ada"
                )
        );

        mockMvc.perform(get("/api/debit-cards/{cardReference}", debitCard.getCardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(secondCustomer.getEmail()))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Debit card not found"))
                .andExpect(jsonPath("$.detail").value("Debit card not found"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(debitCard.getCardReference())))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void customerCanRetrieveOnlyOwnedCardsWithStablePagination() throws Exception {
        Account secondAccount = accountRepository.save(
                Account.createNew(
                        "98765432109876",
                        "Test 2",
                        AccountType.CURRENT,
                        customer,
                        LedgerAccount.createNew(
                                "3".repeat(32),
                                LedgerAccountType.LIABILITY,
                                CurrencyCode.USD
                        )
                )
        );

        DebitCard secondDebitCard = debitCardRepository.save(
                DebitCard.createNew(
                        "4".repeat(32),
                        "4321",
                        YearMonth.now(clock).plusYears(4),
                        YearMonth.now(clock),
                        secondAccount
                )
        );

        Customer secondCustomer = customerRepository.save(
                Customer.createNew(
                        "example@customer.com",
                        "{bcrypt}password-hash",
                        "Lovelace Ada"
                )
        );

        Account secondCustomerAccount = accountRepository.save(
                Account.createNew(
                        "14725836906542",
                        "Customer 2",
                        AccountType.CURRENT,
                        secondCustomer,
                        LedgerAccount.createNew(
                                "5".repeat(32),
                                LedgerAccountType.LIABILITY,
                                CurrencyCode.EUR
                        )
                )
        );

        DebitCard secondCustomerDebitCard = debitCardRepository.save(
                DebitCard.createNew(
                        "6".repeat(32),
                        "9874",
                        YearMonth.of(2033, 5),
                        YearMonth.now(clock),
                        secondCustomerAccount

                )
        );

        Timestamp sharedTimestamp = Timestamp.from(Instant.parse("2026-09-25T12:00:00Z"));

        int updatedCards = jdbcTemplate.update(
                "UPDATE cards SET created_at = ? WHERE id IN (?, ?)",
                sharedTimestamp,
                debitCard.getId(),
                secondDebitCard.getId()
        );

        assertThat(updatedCards)
                .isEqualTo(2);

        MvcResult firstResult = mockMvc.perform(get("/api/debit-cards")
                        .param("page", "0")
                        .param("size", "1")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].fundingAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].customer").doesNotHaveJsonPath())
                .andReturn();

        JsonNode firstRoot = objectMapper.readTree(firstResult.getResponse().getContentAsString());

        List<DebitCardRetrieveResponse> firstPage =
                objectMapper.readerForListOf(DebitCardRetrieveResponse.class)
                        .readValue(firstRoot.get("content"));

        assertThat(firstPage)
                .containsExactly(new DebitCardRetrieveResponse(
                        secondDebitCard.getCardReference(),
                        secondDebitCard.getLastFourDigits(),
                        secondDebitCard.getStatus(),
                        secondDebitCard.getExpirationYearMonth(),
                        secondDebitCard.getFundingAccount().getAccountNumber()
                ));

        assertThat(firstRoot.get("page").get("number").asInt())
                .isEqualTo(0);

        assertThat(firstRoot.get("page").get("size").asInt())
                .isEqualTo(1);

        assertThat(firstRoot.get("page").get("totalElements").asInt())
                .isEqualTo(2);

        assertThat(firstRoot.get("page").get("totalPages").asInt())
                .isEqualTo(2);

        MvcResult secondResult = mockMvc.perform(get("/api/debit-cards")
                        .param("page", "1")
                        .param("size", "1")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        JsonNode secondRoot = objectMapper.readTree(secondResult.getResponse().getContentAsString());

        List<DebitCardRetrieveResponse> secondPage =
                objectMapper.readerForListOf(DebitCardRetrieveResponse.class)
                        .readValue(secondRoot.get("content"));

        assertThat(secondPage)
                .containsExactly(new DebitCardRetrieveResponse(
                        debitCard.getCardReference(),
                        debitCard.getLastFourDigits(),
                        debitCard.getStatus(),
                        debitCard.getExpirationYearMonth(),
                        debitCard.getFundingAccount().getAccountNumber()
                ));

        assertThat(secondRoot.get("page").get("number").asInt())
                .isEqualTo(1);

        assertThat(secondRoot.get("page").get("size").asInt())
                .isEqualTo(1);

        assertThat(secondRoot.get("page").get("totalElements").asInt())
                .isEqualTo(2);

        assertThat(secondRoot.get("page").get("totalPages").asInt())
                .isEqualTo(2);

        assertThat(firstPage.getFirst().cardReference())
                .isNotEqualTo(secondPage.getFirst().cardReference());

        assertThat(List.of(
                        firstPage.getFirst().cardReference(),
                        secondPage.getFirst().cardReference()
                )
        ).doesNotContain(secondCustomerDebitCard.getCardReference());

        assertThat(debitCardRepository.count())
                .isEqualTo(3);
    }

    @Test
    void customerWithNoDebitCardsReceivesEmptyPage() throws Exception {
        Customer secondCustomer = customerRepository.save(
                Customer.createNew(
                        "example@customer.com",
                        "{bcrypt}password-hash",
                        "Lovelace Ada"
                )
        );

        MvcResult result = mockMvc.perform(get("/api/debit-cards")
                        .param("page", "0")
                        .param("size", "10")
                        .with(jwt().jwt(jwt -> jwt.subject(secondCustomer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        List<DebitCardRetrieveResponse> response =
                objectMapper.readerForListOf(DebitCardRetrieveResponse.class)
                        .readValue(root.get("content"));

        assertThat(response)
                .isEmpty();

        assertThat(debitCardRepository.count())
                .isOne();

        assertThat(root.get("page").get("totalElements").asInt())
                .isEqualTo(0);

        assertThat(root.get("page").get("totalPages").asInt())
                .isEqualTo(0);

    }
}
