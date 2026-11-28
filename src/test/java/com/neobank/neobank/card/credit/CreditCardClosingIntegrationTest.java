package com.neobank.neobank.card.credit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.credit.dto.CreditCardClosingRequest;
import com.neobank.neobank.card.credit.dto.CreditCardClosingResponse;
import com.neobank.neobank.card.credit.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.credit.dto.PayoffQuoteResponse;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import com.neobank.neobank.creditaccount.CreditStatement;
import com.neobank.neobank.creditaccount.CreditStatementJob;
import com.neobank.neobank.internalaccount.InternalAccount;
import com.neobank.neobank.internalaccount.InternalAccountPurpose;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountStatus;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(MutableClockConfiguration.class)
class CreditCardClosingIntegrationTest extends CardIntegrationTestSupport {

    @Autowired
    private MutableClock mutableClock;

    @Autowired
    private CreditStatementJob creditStatementJob;

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

        giveCreditBalance(issued.cardReference(), "250.00");

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

    @Test
    void closingInTheFirstCycleChargesNoInterestAndPaysOffTheBalance() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T10:00:00Z");

        CreditCardClosingResponse closing = closed(closeCard(issued.cardReference(), quote.quoteId(), account, "3".repeat(32), "2026-01-25T11:00:00Z"));

        assertThat(closing.cardReference()).isEqualTo(issued.cardReference());
        assertThat(closing.creditAccountNumber()).isEqualTo(issued.creditAccountNumber());
        assertThat(closing.payoffAccountNumber()).isEqualTo(account.getAccountNumber());
        assertThat(closing.interestCharged()).isEqualByComparingTo("0.00");

        CreditStatement closingStatement = statements().getLast();

        assertThat(closingStatement.getStatementReference()).isEqualTo(closing.closingStatementReference());
        assertThat(closingStatement.getPeriodStart()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(closingStatement.getStatementDate()).isEqualTo(LocalDate.of(2026, 1, 26));
        assertThat(closingStatement.getOpeningBalance()).isEqualByComparingTo("0.00");
        assertThat(closingStatement.getClosingBalance()).isEqualByComparingTo("0.00");
        assertThat(closingStatement.getMinimumPayment()).isEqualByComparingTo("0.00");
        assertLinesAddUpToClosingBalance(issued.cardReference(), closingStatement);

        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(cardLedger(issued.cardReference()).getStatus()).isEqualTo(LedgerAccountStatus.CLOSED);

        mockMvc.perform(get("/api/credit-cards/{cardReference}", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.balance").value(0.00));

        mutableClock.setInstant(Instant.parse("2026-02-15T00:15:00Z"));
        creditStatementJob.generateDueStatements();

        assertThat(statements()).hasSize(1);
    }

    @Test
    void closingWithoutGraceChargesTheQuotedInterest() throws Exception {
        Account payoffAccount = savedPayoffAccount("1000.00");

        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        mutableClock.setInstant(Instant.parse("2026-02-15T00:15:00Z"));
        creditStatementJob.generateDueStatements();

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-03-20T10:00:00Z");

        CreditCardClosingResponse closing = closed(closeCard(issued.cardReference(), quote.quoteId(), payoffAccount, "3".repeat(32), "2026-03-20T11:00:00Z"));

        assertThat(closing.interestCharged()).isEqualByComparingTo(quote.accruedInterest());

        CreditStatement closingStatement = statements().getLast();

        assertThat(closingStatement.getPeriodStart()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(closingStatement.getStatementDate()).isEqualTo(LocalDate.of(2026, 3, 21));
        assertThat(closingStatement.getInterestTransaction()).isNotNull();
        assertLinesAddUpToClosingBalance(issued.cardReference(), closingStatement);

        assertThat(balanceOf(payoffAccount.getAccountNumber())).isEqualByComparingTo(new BigDecimal("1000.00").subtract(quote.payoffAmount()));
        assertThat(cardLedger(issued.cardReference()).getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void closingWithACreditBalancePaysItOut() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        giveCreditBalance(issued.cardReference(), "250.00");

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T10:00:00Z");

        closed(closeCard(issued.cardReference(), quote.quoteId(), account, "3".repeat(32), "2026-01-25T11:00:00Z"));

        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("250.00");
        assertThat(cardLedger(issued.cardReference()).getStatus()).isEqualTo(LedgerAccountStatus.CLOSED);
    }

    @Test
    void quoteExpiresAtMidnight() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T23:59:00Z");

        closeCard(issued.cardReference(), quote.quoteId(), account, "3".repeat(32), "2026-01-26T00:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Payoff quote expired"))
                .andExpect(jsonPath("$.detail").value("Payoff quote not found or expired"));

        assertThat(cardLedger(issued.cardReference()).getStatus()).isEqualTo(LedgerAccountStatus.ACTIVE);
    }

    @Test
    void purchaseAfterTheQuoteInvalidatesIt() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T10:00:00Z");

        purchase(issued.cardReference(), "10.00", "3".repeat(32), "2026-01-25T10:30:00Z");

        closeCard(issued.cardReference(), quote.quoteId(), account, "4".repeat(32), "2026-01-25T11:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The card's balance changed after the quote was made"));

        assertThat(cardLedger(issued.cardReference()).getBalance()).isEqualByComparingTo("610.00");
    }

    @Test
    void quoteOfAnotherCardIsRejected() throws Exception {
        CreditCardIssueResponse quotedCard = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        CreditCardIssueResponse otherCard = issueAndActivateCreditCard("2026-01-15T10:00:00Z", "9".repeat(32));

        PayoffQuoteResponse quote = quotePayoff(quotedCard.cardReference(), "2026-01-25T10:00:00Z");

        closeCard(otherCard.cardReference(), quote.quoteId(), account, "3".repeat(32), "2026-01-25T11:00:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Payoff quote not found or expired"));

        assertThat(cardLedger(otherCard.cardReference()).getStatus()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(cardLedger(quotedCard.cardReference()).getStatus()).isEqualTo(LedgerAccountStatus.ACTIVE);
    }

    @Test
    void refundAfterClosingGoesToThePayoffAccount() throws Exception {
        Account payoffAccount = savedPayoffAccount("1000.00");

        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        String purchaseReference = purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T10:00:00Z");
        closed(closeCard(issued.cardReference(), quote.quoteId(), payoffAccount, "3".repeat(32), "2026-01-25T11:00:00Z"));

        assertThat(balanceOf(payoffAccount.getAccountNumber())).isEqualByComparingTo("400.00");

        mutableClock.setInstant(Instant.parse("2026-01-28T10:00:00Z"));

        refund(account.getAccountNumber(), purchaseReference, "4".repeat(32), customer.getEmail())
                .andExpect(status().isCreated());

        assertThat(balanceOf(payoffAccount.getAccountNumber())).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("0.00");
        assertThat(cardLedger(issued.cardReference()).getBalance()).isEqualByComparingTo("0.00");
        assertThat(cardLedger(issued.cardReference()).getStatus()).isEqualTo(LedgerAccountStatus.CLOSED);
    }

    @Test
    void closingTwiceWithSameIdempotencyKeyClosesOnce() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard("2026-01-15T10:00:00Z");
        purchase(issued.cardReference(), "600.00", "2".repeat(32), "2026-01-20T10:00:00Z");

        PayoffQuoteResponse quote = quotePayoff(issued.cardReference(), "2026-01-25T10:00:00Z");

        CreditCardClosingResponse first = closed(closeCard(issued.cardReference(), quote.quoteId(), account, "3".repeat(32), "2026-01-25T11:00:00Z"));
        CreditCardClosingResponse replay = closed(closeCard(issued.cardReference(), quote.quoteId(), account, "3".repeat(32), "2026-01-25T11:05:00Z"));

        assertThat(replay).isEqualTo(first);
        assertThat(balanceOf(account.getAccountNumber())).isEqualByComparingTo("0.00");

        closeCard(issued.cardReference(), quote.quoteId(), account, "4".repeat(32), "2026-01-25T11:10:00Z")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Credit account closed"));

        mockMvc.perform(post("/api/credit-cards/{cardReference}/payoff-quotes", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Credit account closed"));
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

    private ResultActions closeCard(String cardReference, String quoteId, Account payoffAccount, String idempotencyKey, String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        return mockMvc.perform(post("/api/credit-cards/{cardReference}/close", cardReference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CreditCardClosingRequest(quoteId, payoffAccount.getAccountNumber(), null)
                ))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))));
    }

    private CreditCardClosingResponse closed(ResultActions closing) throws Exception {
        String body = closing.andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(body, CreditCardClosingResponse.class);
    }

    private void assertLinesAddUpToClosingBalance(String cardReference, CreditStatement statement) throws Exception {
        String body = mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}/lines",
                        cardReference, statement.getStatementReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        BigDecimal balance = statement.getOpeningBalance();

        for(JsonNode line : objectMapper.readTree(body).get("content")) {
            BigDecimal amount = line.get("appliedAmount").decimalValue();
            balance = "DEBIT".equals(line.get("direction").asString()) ? balance.add(amount) : balance.subtract(amount);
        }

        assertThat(balance).isEqualByComparingTo(statement.getClosingBalance());
    }

    private LedgerAccount cardLedger(String cardReference) {
        return transactionTemplate.execute(status -> {
            LedgerAccount ledgerAccount = creditCardRepository.findByCardReference(cardReference).orElseThrow()
                    .getCreditAccount().getLedgerAccount();
            ledgerAccount.getBalance();
            return ledgerAccount;
        });
    }

    private Account savedPayoffAccount(String balance) {
        Account payoffAccount = Account.createNew("98765432100123", "2".repeat(32), "payoff", AccountType.CURRENT, customer, CurrencyCode.TRY);
        payoffAccount.getLedgerAccount().credit(new BigDecimal(balance));
        return accountRepository.save(payoffAccount);
    }

    private List<CreditStatement> statements() {
        return creditStatementRepository.findAll(Sort.by("statementDate"));
    }

    private CreditCardIssueResponse issueAndActivateCreditCard(String at) throws Exception {
        return issueAndActivateCreditCard(at, "1".repeat(32));
    }

    private CreditCardIssueResponse issueAndActivateCreditCard(String at, String idempotencyKey) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        return issueAndActivateCreditCard(CurrencyCode.TRY, idempotencyKey);
    }

    private String purchase(String cardReference, String amount, String idempotencyKey, String at) throws Exception {
        mutableClock.setInstant(Instant.parse(at));

        return transactionReferenceOf(purchase(cardReference, account.getAccountNumber(), amount, CurrencyCode.TRY, idempotencyKey));
    }
}
