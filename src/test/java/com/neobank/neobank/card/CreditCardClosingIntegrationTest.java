package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.PayoffQuoteResponse;
import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.creditaccount.CreditStatement;
import com.neobank.neobank.creditaccount.CreditStatementJob;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(MutableClockConfiguration.class)
class CreditCardClosingIntegrationTest extends DebitCardIntegrationTestSupport {

    @Autowired
    private MutableClock mutableClock;

    @Autowired
    private CreditCardRepository creditCardRepository;

    @Autowired
    private CreditStatementJob creditStatementJob;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
    void quoteInTheFirstCycleHasNoInterestAndExpiresAtMidnight() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T10:00:00Z");

        assertThat(quote.quoteId()).isNotBlank();
        assertThat(quote.cardReference()).isEqualTo(issued.cardReference());
        assertThat(quote.currency()).isEqualTo(CurrencyCode.TRY);
        assertThat(quote.balance()).isEqualByComparingTo("600.00");
        assertThat(quote.accruedInterest()).isEqualByComparingTo("0.00");
        assertThat(quote.payoffAmount()).isEqualByComparingTo("600.00");
        assertThat(quote.expiresAt()).isEqualTo(Instant.parse("2026-01-26T00:00:00Z"));
        assertThat(statements()).isEmpty();
    }

    @Test
    void quoteWithoutGraceWritesTheDueStatementFirstAndChargesInterestThroughToday() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        mutableClock.setInstant(Instant.parse("2026-02-15T00:15:00Z"));
        creditStatementJob.generateDueStatements();

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-03-20T10:00:00Z");

        List<CreditStatement> statements = statements();

        assertThat(statements).extracting(CreditStatement::getStatementDate)
                .containsExactly(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15));
        assertThat(quote.balance()).isEqualByComparingTo(statements.getLast().getClosingBalance());
        assertThat(quote.accruedInterest()).isPositive();
        assertThat(quote.payoffAmount()).isEqualByComparingTo(quote.balance().add(quote.accruedInterest()));
    }

    @Test
    void quoteBeforeTheDueDateOfAnUnpaidStatementWaivesInterest() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        mutableClock.setInstant(Instant.parse("2026-02-15T00:15:00Z"));
        creditStatementJob.generateDueStatements();

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-03-01T10:00:00Z");

        assertThat(quote.accruedInterest()).isEqualByComparingTo("0.00");
        assertThat(quote.payoffAmount()).isEqualByComparingTo("600.00");
    }

    @Test
    void quoteWithACreditBalanceIsNegative() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");

        transactionTemplate.executeWithoutResult(status ->
                creditCardRepository.findByCardReference(issued.cardReference()).orElseThrow()
                        .getCreditAccount().getLedgerAccount().credit(new BigDecimal("250.00"))
        );

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T10:00:00Z");

        assertThat(quote.balance()).isEqualByComparingTo("-250.00");
        assertThat(quote.payoffAmount()).isEqualByComparingTo("-250.00");
    }

    @Test
    void customerCannotQuoteAnotherCustomersCard() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");

        mockMvc.perform(post("/api/credit-cards/{cardReference}/payoff-quotes", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject("other@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit card not found"));
    }

    private PayoffQuoteResponse quotePayoff(String cardReference, String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        String body = mockMvc.perform(post("/api/credit-cards/{cardReference}/payoff-quotes", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(body, PayoffQuoteResponse.class);
    }

    private List<CreditStatement> statements() {
        return creditStatementRepository.findAll(Sort.by("statementDate"));
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

    private void purchase(String cardReference, String amount, String idempotencyKey, String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CardPurchaseRequest(
                                account.getAccountNumber(),
                                CurrencyCode.TRY,
                                new BigDecimal(amount),
                                null,
                                "Test merchant"
                        )))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated());
    }
}
