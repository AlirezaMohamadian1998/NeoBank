package com.neobank.neobank.card;

import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.dto.CardSpendingLimitsResponse;
import com.neobank.neobank.card.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardRepaymentRequest;
import com.neobank.neobank.card.dto.CreditCardRepaymentResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import com.neobank.neobank.card.dto.CreditCardTransactionFilter;
import com.neobank.neobank.card.dto.CreditStatementResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCreditCardRepaymentException;
import com.neobank.neobank.creditaccount.CreditStatementNotFoundException;
import com.neobank.neobank.creditaccount.RepaymentExceedsBalanceException;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CreditCardController.class)
@Import(SecurityConfig.class)
class CreditCardControllerTest {

    private static final String EMAIL = "customer@example.com";

    private static final String CARD_REFERENCE = "2".repeat(32);

    private static final String IDEMPOTENCY_KEY = "1".repeat(32);

    private static final String STATEMENT_REFERENCE = "3".repeat(32);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreditCardService creditCardService;

    @MockitoBean
    private CreditCardRepaymentService creditCardRepaymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void issueCreditCardReturnsCreatedCard() throws Exception {
        CreditCardIssueRequest request = new CreditCardIssueRequest(CurrencyCode.USD);

        given(creditCardService.issueCreditCard(EMAIL, request, IDEMPOTENCY_KEY))
                .willReturn(new CreditCardIssueResponse(
                        CARD_REFERENCE,
                        YearMonth.of(2031, 9),
                        "5678",
                        "55".repeat(7),
                        new BigDecimal("5000.00"),
                        CurrencyCode.USD
                ));

        mockMvc.perform(post("/api/credit-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cardReference").value(CARD_REFERENCE))
                .andExpect(jsonPath("$.lastFourDigits").value("5678"))
                .andExpect(jsonPath("$.creditAccountNumber").value("55".repeat(7)))
                .andExpect(jsonPath("$.creditLimit").value(5000.00))
                .andExpect(jsonPath("$.currency").value("USD"));

        verify(creditCardService).issueCreditCard(EMAIL, request, IDEMPOTENCY_KEY);
    }

    @Test
    void issueCreditCardReturnsValidationProblemWhenCurrencyIsMissing() throws Exception {
        mockMvc.perform(post("/api/credit-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(creditCardService);
    }

    @Test
    void issueCreditCardReturnsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/credit-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreditCardIssueRequest(CurrencyCode.USD)))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(creditCardService);
    }

    @Test
    void getCreditCardReturnsCardWithCreditFigures() throws Exception {
        given(creditCardService.getCreditCard(CARD_REFERENCE, EMAIL)).willReturn(retrieveResponse(CardStatus.ACTIVE));

        mockMvc.perform(get("/api/credit-cards/{cardReference}", CARD_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardReference").value(CARD_REFERENCE))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.creditLimit").value(5000.00))
                .andExpect(jsonPath("$.balance").value(400.00))
                .andExpect(jsonPath("$.availableCredit").value(4600.00))
                .andExpect(jsonPath("$.annualInterestRate").value(0.2499))
                .andExpect(jsonPath("$.nextStatementDate").value("2026-10-15"))
                .andExpect(jsonPath("$.spendingLimits.dailyLimit").value(2500.00));
    }

    @Test
    void getCreditCardReturnsNotFoundProblem() throws Exception {
        given(creditCardService.getCreditCard(CARD_REFERENCE, EMAIL))
                .willThrow(new CreditCardNotFoundException("Credit card not found"));

        mockMvc.perform(get("/api/credit-cards/{cardReference}", CARD_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit card not found"))
                .andExpect(jsonPath("$.detail").value("Credit card not found"));
    }

    @Test
    void getCreditCardRejectsInvalidCardReference() throws Exception {
        mockMvc.perform(get("/api/credit-cards/{cardReference}", "not-a-reference")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(creditCardService);
    }

    @Test
    void getCreditCardsReturnsPagedResponse() throws Exception {
        given(creditCardService.getCreditCards(any(String.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(retrieveResponse(CardStatus.ACTIVE))));

        mockMvc.perform(get("/api/credit-cards")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].cardReference").value(CARD_REFERENCE))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void changeCardStatusReturnsUpdatedCard() throws Exception {
        given(creditCardService.changeCardStatus(CARD_REFERENCE, EMAIL, CardStatus.FROZEN))
                .willReturn(retrieveResponse(CardStatus.FROZEN));

        mockMvc.perform(patch("/api/credit-cards/{cardReference}/status/{status}", CARD_REFERENCE, "FROZEN")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void updateSpendingLimitsReturnsUpdatedCard() throws Exception {
        CardSpendingLimitsUpdateRequest request = new CardSpendingLimitsUpdateRequest(new BigDecimal("100.00"), new BigDecimal("300.00"));

        given(creditCardService.updateSpendingLimits(CARD_REFERENCE, EMAIL, request))
                .willReturn(retrieveResponse(CardStatus.ACTIVE));

        mockMvc.perform(put("/api/credit-cards/{cardReference}/spending-limits", CARD_REFERENCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk());

        verify(creditCardService).updateSpendingLimits(CARD_REFERENCE, EMAIL, request);
    }

    @Test
    void repayReturnsCreatedRepayment() throws Exception {
        CreditCardRepaymentRequest request = new CreditCardRepaymentRequest("12345678900987", new BigDecimal("250.00"), null);

        given(creditCardRepaymentService.repay(CARD_REFERENCE, request, IDEMPOTENCY_KEY, EMAIL))
                .willReturn(new CreditCardRepaymentResponse(
                        "a".repeat(32),
                        CARD_REFERENCE,
                        "55".repeat(7),
                        "12345678900987",
                        new BigDecimal("250.00"),
                        CurrencyCode.USD,
                        new BigDecimal("250.00"),
                        CurrencyCode.USD,
                        new BigDecimal("750.00"),
                        new BigDecimal("150.00"),
                        Instant.parse("2026-09-23T12:00:00Z")
                ));

        mockMvc.perform(post("/api/credit-cards/{cardReference}/repayments", CARD_REFERENCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionReference").value("a".repeat(32)))
                .andExpect(jsonPath("$.sourceAccountBalanceAfter").value(750.00))
                .andExpect(jsonPath("$.creditCardBalanceAfter").value(150.00));
    }

    @Test
    void repayReturnsValidationProblemWhenAmountIsMissing() throws Exception {
        mockMvc.perform(post("/api/credit-cards/{cardReference}/repayments", CARD_REFERENCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountNumber\":\"12345678900987\"}")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(creditCardRepaymentService);
    }

    @Test
    void repayReturnsConflictWhenRepaymentExceedsBalance() throws Exception {
        CreditCardRepaymentRequest request = new CreditCardRepaymentRequest("12345678900987", new BigDecimal("250.00"), null);

        given(creditCardRepaymentService.repay(CARD_REFERENCE, request, IDEMPOTENCY_KEY, EMAIL))
                .willThrow(new RepaymentExceedsBalanceException("Repayment exceeds the outstanding balance of 100.00"));

        mockMvc.perform(post("/api/credit-cards/{cardReference}/repayments", CARD_REFERENCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Repayment exceeds balance"))
                .andExpect(jsonPath("$.detail").value("Repayment exceeds the outstanding balance of 100.00"));
    }

    @Test
    void repayReturnsBadRequestForInvalidRepayment() throws Exception {
        CreditCardRepaymentRequest request = new CreditCardRepaymentRequest("12345678900987", new BigDecimal("250.00"), null);

        given(creditCardRepaymentService.repay(CARD_REFERENCE, request, IDEMPOTENCY_KEY, EMAIL))
                .willThrow(new InvalidCreditCardRepaymentException("Lock ID is not required for same currency repayment"));

        mockMvc.perform(post("/api/credit-cards/{cardReference}/repayments", CARD_REFERENCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid credit card repayment"))
                .andExpect(jsonPath("$.detail").value("Lock ID is not required for same currency repayment"));
    }

    @Test
    void getStatementsReturnsPagedStatements() throws Exception {
        given(creditCardService.getStatements(any(String.class), any(String.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(statementResponse())));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements", CARD_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].statementReference").value(STATEMENT_REFERENCE))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void getStatementReturnsStatement() throws Exception {
        given(creditCardService.getStatement(CARD_REFERENCE, STATEMENT_REFERENCE, EMAIL)).willReturn(statementResponse());

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}", CARD_REFERENCE, STATEMENT_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statementReference").value(STATEMENT_REFERENCE))
                .andExpect(jsonPath("$.periodStart").value("2026-08-15"))
                .andExpect(jsonPath("$.statementDate").value("2026-09-15"))
                .andExpect(jsonPath("$.dueDate").value("2026-10-10"))
                .andExpect(jsonPath("$.openingBalance").value(600.00))
                .andExpect(jsonPath("$.closingBalance").value(510.27))
                .andExpect(jsonPath("$.averageDailyBalance").value(535.71))
                .andExpect(jsonPath("$.interestCharged").value(10.27))
                .andExpect(jsonPath("$.minimumPayment").value(25.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void getStatementReturnsNotFoundProblem() throws Exception {
        given(creditCardService.getStatement(CARD_REFERENCE, STATEMENT_REFERENCE, EMAIL))
                .willThrow(new CreditStatementNotFoundException("Credit statement not found"));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}", CARD_REFERENCE, STATEMENT_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit statement not found"));
    }

    @Test
    void getStatementRejectsInvalidStatementReference() throws Exception {
        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}", CARD_REFERENCE, "not-a-reference")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(creditCardService);
    }

    @Test
    void getTransactionsPassesFilterAndNewestFirstSortByDefault() throws Exception {
        given(creditCardService.getTransactions(any(String.class), any(CreditCardTransactionFilter.class), any(String.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(statementLine())));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/transactions", CARD_REFERENCE)
                        .param("type", "CREDIT_CARD_REPAYMENT")
                        .param("dateFrom", "2026-08-01T00:00:00Z")
                        .param("dateTo", "2026-09-01T00:00:00Z")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionType").value("CREDIT_CARD_REPAYMENT"))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        ArgumentCaptor<CreditCardTransactionFilter> filterCaptor = ArgumentCaptor.forClass(CreditCardTransactionFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(creditCardService).getTransactions(eq(CARD_REFERENCE), filterCaptor.capture(), eq(EMAIL), pageableCaptor.capture());
        assertThat(filterCaptor.getValue()).isEqualTo(new CreditCardTransactionFilter(
                TransactionType.CREDIT_CARD_REPAYMENT,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z")
        ));
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"));
    }

    @Test
    void getTransactionsRejectsDateFromAfterDateTo() throws Exception {
        mockMvc.perform(get("/api/credit-cards/{cardReference}/transactions", CARD_REFERENCE)
                        .param("dateFrom", "2026-09-01T00:00:00Z")
                        .param("dateTo", "2026-08-01T00:00:00Z")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(creditCardService);
    }

    @Test
    void getTransactionsRejectsUnknownType() throws Exception {
        mockMvc.perform(get("/api/credit-cards/{cardReference}/transactions", CARD_REFERENCE)
                        .param("type", "NOT_A_TYPE")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(creditCardService);
    }

    @Test
    void getTransactionsReturnsNotFoundProblem() throws Exception {
        given(creditCardService.getTransactions(any(String.class), any(CreditCardTransactionFilter.class), any(String.class), any(Pageable.class)))
                .willThrow(new CreditCardNotFoundException("Credit card not found"));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/transactions", CARD_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit card not found"));
    }

    @Test
    void getStatementLinesReturnsPagedLinesOldestFirstByDefault() throws Exception {
        given(creditCardService.getStatementLines(any(String.class), any(String.class), any(String.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(statementLine())));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}/lines", CARD_REFERENCE, STATEMENT_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionType").value("CREDIT_CARD_REPAYMENT"))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].appliedAmount").value(400.00))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(creditCardService).getStatementLines(eq(CARD_REFERENCE), eq(STATEMENT_REFERENCE), eq(EMAIL), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(Sort.Order.asc("createdAt"));
    }

    @Test
    void getStatementLinesReturnsNotFoundProblem() throws Exception {
        given(creditCardService.getStatementLines(any(String.class), any(String.class), any(String.class), any(Pageable.class)))
                .willThrow(new CreditStatementNotFoundException("Credit statement not found"));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}/lines", CARD_REFERENCE, STATEMENT_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit statement not found"));
    }

    @Test
    void getStatementLinesRejectsInvalidStatementReference() throws Exception {
        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}/lines", CARD_REFERENCE, "not-a-reference")
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(creditCardService);
    }

    private static TransactionHistoryResponse statementLine() {
        return new TransactionHistoryResponse(
                "55".repeat(7),
                "6".repeat(32),
                "7".repeat(32),
                TransactionType.CREDIT_CARD_REPAYMENT,
                EntryDirection.CREDIT,
                new BigDecimal("400.00"),
                CurrencyCode.USD,
                new BigDecimal("400.00"),
                CurrencyCode.USD,
                new BigDecimal("600.00"),
                Instant.parse("2026-09-01T10:00:00Z")
        );
    }

    private static CreditStatementResponse statementResponse() {
        return new CreditStatementResponse(
                STATEMENT_REFERENCE,
                LocalDate.of(2026, 8, 15),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 10, 10),
                new BigDecimal("600.00"),
                new BigDecimal("510.27"),
                new BigDecimal("535.71"),
                new BigDecimal("10.27"),
                new BigDecimal("25.00"),
                CurrencyCode.USD
        );
    }

    private static CreditCardRetrieveResponse retrieveResponse(CardStatus status) {
        return new CreditCardRetrieveResponse(
                CARD_REFERENCE,
                "5678",
                status,
                YearMonth.of(2031, 9),
                "55".repeat(7),
                new BigDecimal("5000.00"),
                new BigDecimal("400.00"),
                new BigDecimal("4600.00"),
                new BigDecimal("0.2499"),
                LocalDate.of(2026, 10, 15),
                CurrencyCode.USD,
                new CardSpendingLimitsResponse(
                        new BigDecimal("1000.00"),
                        new BigDecimal("2500.00"),
                        new BigDecimal("400.00"),
                        CurrencyCode.USD
                )
        );
    }
}
