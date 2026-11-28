package com.neobank.neobank.card;

import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.dto.CardTransactionFilter;
import com.neobank.neobank.card.payment.CardPaymentService;
import com.neobank.neobank.card.payment.dto.CardPurchaseRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CardController.class)
@Import(SecurityConfig.class)
class CardControllerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CardPaymentService cardPaymentService;

    @MockitoBean
    private CardTransactionService cardTransactionService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void makePaymentReturnsCreatedPurchaseForAuthenticatedJwt() throws Exception {
        String idempotencyKey = "1".repeat(32);
        String email = "customer@example.com";
        String cardReference = "2".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                "98".repeat(7),
                CurrencyCode.USD,
                new BigDecimal("100.00"),
                "Test",
                "Test merchant"
        );

        CardPurchaseResponse response = new CardPurchaseResponse(
                "6".repeat(32),
                request.amount(),
                new BigDecimal("4000.00"),
                CurrencyCode.USD,
                CurrencyCode.TRY,
                new BigDecimal("6000.00"),
                request.merchantName(),
                "1234",
                cardReference,
                "12".repeat(7),
                Instant.parse("2026-09-23T12:00:00Z")
        );

        given(cardPaymentService.makePurchase(cardReference, request, idempotencyKey, email))
                .willReturn(response);

        makePayment(cardReference, request, idempotencyKey, email)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").value(response.transactionReference()))
                .andExpect(jsonPath("$.requestedAmount").value(100.00))
                .andExpect(jsonPath("$.debitedAmount").value(4000.00))
                .andExpect(jsonPath("$.requestedCurrency").value(CurrencyCode.USD.name()))
                .andExpect(jsonPath("$.debitedCurrency").value(CurrencyCode.TRY.name()))
                .andExpect(jsonPath("$.balanceAfter").value(6000.00))
                .andExpect(jsonPath("$.merchantName").value(response.merchantName()))
                .andExpect(jsonPath("$.cardLastFourDigits").value(response.cardLastFourDigits()))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.linkedAccountNumber").value(response.linkedAccountNumber()))
                .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()));

        verify(cardPaymentService).makePurchase(cardReference, request, idempotencyKey, email);
    }

    @Test
    void makePaymentAcceptsExternalMerchantWithoutAccountNumberAndNote() throws Exception {
        String idempotencyKey = "1".repeat(32);
        String email = "customer@example.com";
        String cardReference = "2".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                null,
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                null,
                "Test merchant"
        );

        CardPurchaseResponse response = new CardPurchaseResponse(
                "6".repeat(32),
                request.amount(),
                request.amount(),
                CurrencyCode.TRY,
                CurrencyCode.TRY,
                new BigDecimal("750.00"),
                request.merchantName(),
                "1234",
                cardReference,
                "12".repeat(7),
                Instant.parse("2026-09-23T12:00:00Z")
        );

        given(cardPaymentService.makePurchase(cardReference, request, idempotencyKey, email))
                .willReturn(response);

        makePayment(cardReference, request, idempotencyKey, email)
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").value(response.transactionReference()));

        verify(cardPaymentService).makePurchase(cardReference, request, idempotencyKey, email);
    }

    @Test
    void makePaymentReturnsValidationProblemForInvalidRequest() throws Exception {
        String idempotencyKey = "1".repeat(32);
        String email = "customer@example.com";
        String cardReference = "2".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                "123",
                null,
                new BigDecimal("100.001"),
                " ",
                ""
        );

        makePayment(cardReference, request, idempotencyKey, email)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.instance").value("/api/cards/%s/payments".formatted(cardReference)))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.merchantAccountNumber").value("Account number must be 14 digits"))
                .andExpect(jsonPath("$.errors.currency").value("Currency cannot be null"))
                .andExpect(jsonPath("$.errors.amount").value("Amount must be a valid decimal number"))
                .andExpect(jsonPath("$.errors.note").value("Note must not be blank"))
                .andExpect(jsonPath("$.errors.merchantName").value("Merchant name must not be null or blank"));

        verifyNoInteractions(cardPaymentService);
    }

    @Test
    void makePaymentRejectsInvalidCardReference() throws Exception {
        String idempotencyKey = "1".repeat(32);
        String email = "customer@example.com";
        String invalidCardReference = "Z".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                "98".repeat(7),
                CurrencyCode.TRY,
                new BigDecimal("100.00"),
                "Test",
                "Test merchant"
        );

        makePayment(invalidCardReference, request, idempotencyKey, email)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.instance").value("/api/cards/%s/payments".formatted(invalidCardReference)))
                .andExpect(jsonPath("$.errors.cardReference").value("Card reference must be 32 hexadecimal characters"))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(cardPaymentService);
    }

    @Test
    void makePaymentReturnsUnauthorizedWithoutAuthentication() throws Exception {
        String idempotencyKey = "1".repeat(32);
        String cardReference = "2".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                "98".repeat(7),
                CurrencyCode.TRY,
                new BigDecimal("100.00"),
                "Test",
                "Test merchant"
        );

        mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(cardPaymentService);
    }

    @Test
    void makePaymentReturnsBadRequestWhenIdempotencyKeyHeaderIsMissing() throws Exception {
        String email = "customer@example.com";
        String cardReference = "2".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                "98".repeat(7),
                CurrencyCode.TRY,
                new BigDecimal("100.00"),
                "Test",
                "Test merchant"
        );

        mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(cardPaymentService);
    }

    @Test
    void getTransactionsPassesFilterAndNewestFirstSortByDefault() throws Exception {
        String cardReference = "2".repeat(32);
        ArgumentCaptor<CardTransactionFilter> filterCaptor = ArgumentCaptor.forClass(CardTransactionFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        given(cardTransactionService.getTransactions(any(), any(), any(), any())).willReturn(Page.empty());

        mockMvc.perform(get("/api/cards/{cardReference}/transactions", cardReference)
                        .param("dateFrom", "2026-08-01T00:00:00Z")
                        .param("dateTo", "2026-09-01T00:00:00Z")
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        verify(cardTransactionService).getTransactions(
                eq(cardReference), filterCaptor.capture(), eq("customer@example.com"), pageableCaptor.capture());
        assertThat(filterCaptor.getValue())
                .isEqualTo(new CardTransactionFilter(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")));
        assertThat(pageableCaptor.getValue().getSort()).containsExactly(Sort.Order.desc("createdAt"));
    }

    @Test
    void getTransactionsRejectsDateFromAfterDateTo() throws Exception {
        mockMvc.perform(get("/api/cards/{cardReference}/transactions", "2".repeat(32))
                        .param("dateFrom", "2026-09-01T00:00:00Z")
                        .param("dateTo", "2026-08-01T00:00:00Z")
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cardTransactionService);
    }

    @Test
    void getTransactionsRejectsInvalidCardReference() throws Exception {
        mockMvc.perform(get("/api/cards/{cardReference}/transactions", "Z".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cardReference").value("Card reference must be 32 hexadecimal characters"));

        verifyNoInteractions(cardTransactionService);
    }

    private ResultActions makePayment(String cardReference, CardPurchaseRequest request, String idempotencyKey, String email) throws Exception {
        return mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(email))));
    }
}
