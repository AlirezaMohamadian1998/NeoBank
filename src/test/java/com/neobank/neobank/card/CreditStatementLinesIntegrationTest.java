package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardRepaymentRequest;
import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditStatement;
import com.neobank.neobank.creditaccount.CreditStatementService;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.MutableClock;
import com.neobank.neobank.shared.MutableClockConfiguration;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(MutableClockConfiguration.class)
class CreditStatementLinesIntegrationTest extends DebitCardIntegrationTestSupport {

    @Autowired
    private MutableClock mutableClock;

    @Autowired
    private CreditStatementService creditStatementService;

    @Autowired
    private CreditCardRepository creditCardRepository;

    @BeforeEach
    void setUpInterestIncomeAccount() {
        internalAccountRepository.save(InternalAccount.createNew(
                InternalAccountPurpose.INTEREST_INCOME,
                LedgerAccount.createNew("9d3c8a21d9e64b5fa2c17e9084bd6d34", LedgerAccountType.INCOME, CurrencyCode.TRY)
        ));
    }

    @AfterEach
    void resetClock() {
        mutableClock.reset();
    }

    @Test
    void statementLinesListTheCycleMovementsOldestFirst() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");

        String firstPurchaseReference = purchase(issued.cardReference(), "1000.00", "2".repeat(32), "2026-01-20T10:00:00Z");
        String secondPurchaseReference = purchase(issued.cardReference(), "300.00", "3".repeat(32), "2026-01-21T10:00:00Z");
        refund(secondPurchaseReference, "4".repeat(32), "2026-01-25T10:00:00Z");
        repay(issued.cardReference(), "400.00", "5".repeat(32), "2026-02-01T10:00:00Z");
        purchase(issued.cardReference(), "50.00", "6".repeat(32), "2026-02-15T00:00:00Z");

        close(issued.cardReference(), "2026-02-15T00:15:00Z");

        CreditStatement statement = statements().getFirst();
        JsonNode lines = lines(issued.cardReference(), statement.getStatementReference());

        assertThat(lines.size()).isEqualTo(4);

        assertThat(lines.get(0).get("transactionType").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(lines.get(0).get("direction").asString()).isEqualTo("DEBIT");
        assertThat(lines.get(0).get("appliedAmount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(lines.get(0).get("transactionReference").asString()).isEqualTo(firstPurchaseReference);
        assertThat(lines.get(0).get("merchantName").asString()).isEqualTo("Test merchant");
        assertThat(lines.get(0).get("cardReference").asString()).isEqualTo(issued.cardReference());
        assertThat(lines.get(0).get("accountNumber").asString()).isEqualTo(issued.creditAccountNumber());

        assertThat(lines.get(1).get("transactionType").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(lines.get(1).get("appliedAmount").decimalValue()).isEqualByComparingTo("300.00");

        assertThat(lines.get(2).get("transactionType").asString()).isEqualTo("CARD_REFUND");
        assertThat(lines.get(2).get("direction").asString()).isEqualTo("CREDIT");
        assertThat(lines.get(2).get("purchaseReference").asString()).isEqualTo(secondPurchaseReference);

        assertThat(lines.get(3).get("transactionType").asString()).isEqualTo("CREDIT_CARD_REPAYMENT");
        assertThat(lines.get(3).get("direction").asString()).isEqualTo("CREDIT");
        assertThat(lines.get(3).get("appliedAmount").decimalValue()).isEqualByComparingTo("400.00");

        assertThat(statement.getClosingBalance()).isEqualByComparingTo("600.00");
        assertLinesAddUpToClosingBalance(statement, lines);
    }

    @Test
    void eachStatementShowsItsOwnInterestChargeAsTheLastLine() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");

        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");
        close(issued.cardReference(), "2026-02-15T00:15:00Z");

        purchase(issued.cardReference(), "50.00", "3".repeat(32), "2026-02-20T10:00:00Z");
        close(issued.cardReference(), "2026-03-15T00:15:00Z");
        close(issued.cardReference(), "2026-04-15T00:15:00Z");

        List<CreditStatement> statements = statements();
        CreditStatement second = statements.get(1);
        CreditStatement third = statements.get(2);

        assertThat(second.getInterestCharged()).isPositive();
        assertThat(third.getInterestCharged()).isPositive();

        JsonNode secondLines = lines(issued.cardReference(), second.getStatementReference());

        assertThat(secondLines.size()).isEqualTo(2);
        assertThat(secondLines.get(0).get("transactionType").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(secondLines.get(1).get("transactionType").asString()).isEqualTo("CREDIT_CARD_INTEREST");
        assertThat(secondLines.get(1).get("direction").asString()).isEqualTo("DEBIT");
        assertThat(secondLines.get(1).get("appliedAmount").decimalValue()).isEqualByComparingTo(second.getInterestCharged());
        assertLinesAddUpToClosingBalance(second, secondLines);

        JsonNode thirdLines = lines(issued.cardReference(), third.getStatementReference());

        assertThat(thirdLines.size()).isEqualTo(1);
        assertThat(thirdLines.get(0).get("transactionType").asString()).isEqualTo("CREDIT_CARD_INTEREST");
        assertThat(thirdLines.get(0).get("appliedAmount").decimalValue()).isEqualByComparingTo(third.getInterestCharged());
        assertLinesAddUpToClosingBalance(third, thirdLines);
    }

    @Test
    void customerCannotSeeAnotherCustomersStatementLines() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "100.00", "2".repeat(32), "2026-01-20T10:00:00Z");
        close(issued.cardReference(), "2026-02-15T00:15:00Z");
        String statementReference = statements().getFirst().getStatementReference();

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}/lines",
                        issued.cardReference(), statementReference)
                        .with(jwt().jwt(jwt -> jwt.subject("other@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit card not found"));
    }

    private void assertLinesAddUpToClosingBalance(CreditStatement statement, JsonNode lines) {
        BigDecimal balance = statement.getOpeningBalance();

        for (JsonNode line : lines) {
            BigDecimal amount = line.get("appliedAmount").decimalValue();
            balance = "DEBIT".equals(line.get("direction").asString()) ? balance.add(amount) : balance.subtract(amount);
        }

        assertThat(balance).isEqualByComparingTo(statement.getClosingBalance());
    }

    private JsonNode lines(String cardReference, String statementReference) throws Exception {
        String body = mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}/lines",
                        cardReference, statementReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(body).get("content");
    }

    private List<CreditStatement> statements() {
        return creditStatementRepository.findAll(Sort.by("statementDate"));
    }

    private void close(String cardReference, String at) {
        Instant instant = Instant.parse(at);
        mutableClock.setInstant(instant);
        CreditAccount creditAccount = creditCardRepository.findByCardReference(cardReference).orElseThrow().getCreditAccount();
        creditStatementService.generateStatementsUpTo(creditAccount.getId(), LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    private CreditCardIssueResponse issueAndActivateCreditCard(String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        String body = mockMvc.perform(post("/api/credit-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreditCardIssueRequest(CurrencyCode.TRY)))
                        .header("Idempotency-Key", "1".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        CreditCardIssueResponse issued = objectMapper.readValue(body, CreditCardIssueResponse.class);

        mockMvc.perform(patch("/api/credit-cards/{cardReference}/status/{status}", issued.cardReference(), "ACTIVE")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk());

        return issued;
    }

    private String purchase(String cardReference, String amount, String idempotencyKey, String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        CardPurchaseRequest request = new CardPurchaseRequest(
                account.getAccountNumber(),
                CurrencyCode.TRY,
                new BigDecimal(amount),
                null,
                "Test merchant"
        );

        String body = mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(body, CardPurchaseResponse.class).transactionReference();
    }

    private void refund(String purchaseReference, String idempotencyKey, String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        mockMvc.perform(post("/api/accounts/{merchantAccountNumber}/card-refunds/{purchaseReference}",
                        account.getAccountNumber(), purchaseReference)
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated());
    }

    private void repay(String cardReference, String amount, String idempotencyKey, String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        mockMvc.perform(post("/api/credit-cards/{cardReference}/repayments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreditCardRepaymentRequest(account.getAccountNumber(), new BigDecimal(amount), null)
                        ))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated());
    }
}
