package com.neobank.neobank.card;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.card.exception.CardExpiredException;
import com.neobank.neobank.card.exception.CardNotActiveException;
import com.neobank.neobank.card.exception.DebitCardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCardPaymentException;
import com.neobank.neobank.card.exception.InvalidCardStatusTransitionException;
import com.neobank.neobank.card.exception.UnsupportedCardStatusException;
import com.neobank.neobank.fx.FxProviderUnavailableException;
import com.neobank.neobank.idempotency.IdempotencyConflictException;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebitCardController.class)
@Import(SecurityConfig.class)
class DebitCardControllerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DebitCardService debitCardService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CardPaymentService cardPaymentService;

    @Test
    void issueDebitCardReturnsCreatedCardForAuthenticatedJwt() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        DebitCardIssueResponse response = new DebitCardIssueResponse(
                "2".repeat(32),
                YearMonth.of(2031, 9),
                accountNumber,
                "1234"
        );

        given(debitCardService.issueDebitCard(email, accountNumber, idempotencyKey))
                .willReturn(response);

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", accountNumber)
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(response.cardReference()))
                .andExpect(jsonPath("$.expirationYearMonth").value(response.expirationYearMonth().toString()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(response.fundingAccountNumber()))
                .andExpect(jsonPath("$.lastFourDigits").value(response.lastFourDigits()));

        verify(debitCardService).issueDebitCard(email, accountNumber, idempotencyKey);
    }

    @Test
    void issueDebitCardReturnsNotFoundWhenFundingAccountIsNotOwned() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        given(debitCardService.issueDebitCard(email, accountNumber, idempotencyKey))
                .willThrow(new AccountNotFoundException());

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", accountNumber)
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(accountNumber)))
                .andExpect(jsonPath("$.status").value(404));

        verify(debitCardService).issueDebitCard(email, accountNumber, idempotencyKey);

    }

    @Test
    void issueDebitCardReturnsValidationProblemForInvalidAccountNumber() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";
        String email = "customer@example.com";
        String accountNumber = "a12345678900987a";

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", accountNumber)
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more parameters"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(accountNumber)))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.accountNumber").value("Account number must be exactly 14 digits"));

        verifyNoInteractions(debitCardService);
    }

    @Test
    void issueDebitCardReturnsUnauthorizedWithoutAuthentication() throws Exception {
        String idempotencyKey = "11111111111111111111111111111111";
        String accountNumber = "12345678900987";

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", accountNumber)
                        .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(debitCardService);
    }

    @Test
    void issueDebitCardReturnsBadRequestWhenIdempotencyKeyHeaderIsMissing() throws Exception {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", accountNumber)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(debitCardService);
    }

    @Test
    void issueDebitCardReturnsBadRequestWhenIdempotencyKeyIsInvalid() throws Exception {
        String idempotencyKey = "ZZzz1111XX1111XXXX111111XX11zzZZ";
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        given(debitCardService.issueDebitCard(email, accountNumber, idempotencyKey))
                .willThrow(new InvalidIdempotencyKeyException("Invalid idempotency key"));

        mockMvc.perform(post("/api/debit-cards/{accountNumber}", accountNumber)
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid idempotency key"))
                .andExpect(jsonPath("$.detail").value("Invalid idempotency key"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(accountNumber)))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getDebitCardReturnsCardForAuthenticatedCustomer() throws Exception {
        String email = "customer@example.com";

        DebitCardRetrieveResponse response = new DebitCardRetrieveResponse(
                "11111111111111111111111111111111",
                "1234",
                CardStatus.ACTIVE,
                YearMonth.of(2031, 9),
                "12345678900987"
        );

        given(debitCardService.getDebitCard(response.cardReference(), email))
                .willReturn(response);

        mockMvc.perform(get("/api/debit-cards/{cardReference}", response.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(response.cardReference()))
                .andExpect(jsonPath("$.lastFourDigits").value(response.lastFourDigits()))
                .andExpect(jsonPath("$.status").value(response.status().toString()))
                .andExpect(jsonPath("$.expirationYearMonth").value(response.expirationYearMonth().toString()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(response.fundingAccountNumber()));

        verify(debitCardService).getDebitCard(response.cardReference(), email);
    }

    @Test
    void getDebitCardReturnsNotFoundProblem() throws Exception {
        String email = "customer@example.com";
        String cardReference = "11111111111111111111111111111111";

        given(debitCardService.getDebitCard(cardReference, email))
                .willThrow(new DebitCardNotFoundException("Debit card not found"));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Debit card not found"))
                .andExpect(jsonPath("$.detail").value("Debit card not found"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(cardReference)))
                .andExpect(jsonPath("$.status").value(404));

        verify(debitCardService).getDebitCard(cardReference, email);
    }

    @Test
    void getDebitCardRejectsInvalidCardReference() throws Exception {
        String email = "customer@example.com";
        String invalidCardReference = "zzzz11111111zzzzzz111111111zzzz";

        mockMvc.perform(get("/api/debit-cards/{cardReference}", invalidCardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more parameters"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s".formatted(invalidCardReference)))
                .andExpect(jsonPath("$.errors.cardReference").value("Card reference must be 32 hexadecimal characters"))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(debitCardService);
    }

    @Test
    void getDebitCardReturnsUnauthorizedWithoutAuthentication() throws Exception {
        String cardReference = "11111111111111111111111111111111";

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(debitCardService);
    }

    @Test
    void getDebitCardsReturnsPagedResponse() throws Exception {
        String email = "customer@example.com";
        Pageable pageable = PageRequest.of(0, 10);

        DebitCardRetrieveResponse firstResponse = new DebitCardRetrieveResponse(
                "11111111111111111111111111111111",
                "1234",
                CardStatus.ACTIVE,
                YearMonth.of(2031, 9),
                "12345678900321"
        );

        DebitCardRetrieveResponse secondResponse = new DebitCardRetrieveResponse(
                "22222222222222222222222222222222",
                "4321",
                CardStatus.FROZEN,
                YearMonth.of(2032, 10),
                "12300123456789"
        );

        Page<DebitCardRetrieveResponse> responses = new PageImpl<>(
                List.of(firstResponse, secondResponse),
                pageable,
                2
        );

        given(debitCardService.getDebitCards(email, pageable))
                .willReturn(responses);

        mockMvc.perform(get("/api/debit-cards")
                        .with(jwt().jwt(jwt -> jwt.subject(email)))
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].cardReference")
                        .value(contains(
                                firstResponse.cardReference(),
                                secondResponse.cardReference()
                        )))
                .andExpect(jsonPath("$.content[*].lastFourDigits")
                        .value(contains(
                                firstResponse.lastFourDigits(),
                                secondResponse.lastFourDigits()
                        )))
                .andExpect(jsonPath("$.content[*].status")
                        .value(contains(
                                CardStatus.ACTIVE.name(),
                                CardStatus.FROZEN.name()
                        )))
                .andExpect(jsonPath("$.content[*].expirationYearMonth")
                        .value(contains(
                                firstResponse.expirationYearMonth().toString(),
                                secondResponse.expirationYearMonth().toString()
                        )))
                .andExpect(jsonPath("$.content[*].fundingAccountNumber")
                        .value(contains(
                                firstResponse.fundingAccountNumber(),
                                secondResponse.fundingAccountNumber()
                        )))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(1));

        verify(debitCardService).getDebitCards(email, pageable);
    }

    @Test
    void getDebitCardsBindsPaginationAndCustomerEmail() throws Exception {
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);

        String email = "customer@example.com";

        given(debitCardService.getDebitCards(anyString(), any()))
                .willReturn(Page.empty());

        mockMvc.perform(get("/api/debit-cards")
                        .param("page", "1")
                        .param("size", "2")
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verify(debitCardService).getDebitCards(emailCaptor.capture(), pageableCaptor.capture());

        String capturedEmail = emailCaptor.getValue();
        Pageable capturedPageable = pageableCaptor.getValue();

        assertThat(capturedEmail)
                .isEqualTo(email);

        assertThat(capturedPageable)
                .isEqualTo(PageRequest.of(1, 2));
    }

    @Test
    void getDebitCardsReturnsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/debit-cards"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(debitCardService);
    }

    @Test
    void changeCardStatusReturnsUpdatedCardForAuthenticatedCustomer() throws Exception {
        String email = "customer@example.com";

        DebitCardRetrieveResponse response = new DebitCardRetrieveResponse(
                "11111111111111111111111111111111",
                "1234",
                CardStatus.ACTIVE,
                YearMonth.of(2031, 9),
                "12345678900321"
        );

        given(debitCardService.changeCardStatus(response.cardReference(), email, CardStatus.ACTIVE))
                .willReturn(response);

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", response.cardReference(), CardStatus.ACTIVE)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(response.cardReference()))
                .andExpect(jsonPath("$.lastFourDigits").value(response.lastFourDigits()))
                .andExpect(jsonPath("$.status").value(response.status().name()))
                .andExpect(jsonPath("$.expirationYearMonth").value(response.expirationYearMonth().toString()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(response.fundingAccountNumber()))
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.fundingAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath());

        verify(debitCardService).changeCardStatus(response.cardReference(), email, CardStatus.ACTIVE);
    }

    @Test
    void changeCardStatusRejectsInvalidCardReference() throws Exception {
        String email = "customer@example.com";
        String invalidCardReference = "11zzz1111yy11xx1kkk111ggg11zzz11";

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", invalidCardReference, CardStatus.ACTIVE)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more parameters"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.instance")
                        .value("/api/debit-cards/%s/status/%s".formatted(invalidCardReference, CardStatus.ACTIVE.name())))
                .andExpect(jsonPath("$.errors.cardReference").value("Card reference must be 32 hexadecimal characters"))
                .andExpect(jsonPath("$.status").value("400"));

        verifyNoMoreInteractions(debitCardService);
    }

    @Test
    void changeCardStatusReturnsBadRequestForUnknownStatus() throws Exception {
        String email = "customer@example.com";
        String cardReference = "11111111111111111111111111111111";
        String unknownStatus = "OPENED";

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, unknownStatus)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoMoreInteractions(debitCardService);
    }

    @Test
    void changeCardStatusReturnsUnauthorizedWithoutAuthentication() throws Exception {
        String cardReference = "11111111111111111111111111111111";

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.ACTIVE.name()))
                .andExpect(status().isUnauthorized());

        verifyNoMoreInteractions(debitCardService);
    }

    @ParameterizedTest
    @MethodSource("changeCardStatusServiceExceptionSource")
    void changeCardStatusReturnsExpectedProblemForServiceException(
            CardStatus status,
            RuntimeException ex,
            String title,
            int httpStatusCode
    ) throws Exception {
        String email = "customer@example.com";
        String cardReference = "11111111111111111111111111111111";

        given(debitCardService.changeCardStatus(cardReference, email, status))
                .willThrow(ex);

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, status.name())
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().is(httpStatusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(ex.getMessage()))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.instance")
                        .value("/api/debit-cards/%s/status/%s".formatted(cardReference, status.name())))
                .andExpect(jsonPath("$.status").value(httpStatusCode));

        verify(debitCardService).changeCardStatus(cardReference, email, status);
    }

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

        mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
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
                .andExpect(jsonPath("$.fundingAccountNumber").value(response.fundingAccountNumber()))
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

        mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
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

        mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s/payments".formatted(cardReference)))
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

        mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", invalidCardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s/payments".formatted(invalidCardReference)))
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

        mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", cardReference)
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

        mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(cardPaymentService);
    }

    @ParameterizedTest
    @MethodSource("makePaymentServiceExceptionSource")
    void makePaymentReturnsExpectedProblemForServiceException(
            RuntimeException ex,
            String title,
            int httpStatusCode
    ) throws Exception {
        String idempotencyKey = "1".repeat(32);
        String email = "customer@example.com";
        String cardReference = "2".repeat(32);

        CardPurchaseRequest request = new CardPurchaseRequest(
                "98".repeat(7),
                CurrencyCode.TRY,
                new BigDecimal("100.00"),
                "Test",
                "Test merchant"
        );

        given(cardPaymentService.makePurchase(cardReference, request, idempotencyKey, email))
                .willThrow(ex);

        mockMvc.perform(post("/api/debit-cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().is(httpStatusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(ex.getMessage()))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.instance").value("/api/debit-cards/%s/payments".formatted(cardReference)))
                .andExpect(jsonPath("$.status").value(httpStatusCode));

        verify(cardPaymentService).makePurchase(cardReference, request, idempotencyKey, email);
    }

    static Stream<Arguments> makePaymentServiceExceptionSource() {
        return Stream.of(
                Arguments.argumentSet(
                        "makePaymentReturnsNotFoundForMissingCard",
                        new DebitCardNotFoundException("Debit card not found"),
                        "Debit card not found",
                        404
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsNotFoundForMissingMerchantAccount",
                        new AccountNotFoundException("Merchant's account not found"),
                        "Account not found",
                        404
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsBadRequestForInvalidCardPayment",
                        new InvalidCardPaymentException("Merchant's account and the card's funding account cannot be same"),
                        "Invalid card payment",
                        400
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsBadRequestForInvalidIdempotencyKey",
                        new InvalidIdempotencyKeyException("Idempotency key is invalid"),
                        "Invalid idempotency key",
                        400
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsConflictForExpiredCard",
                        new CardExpiredException("Card is Expired"),
                        "Card expired",
                        409
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsConflictForInactiveCard",
                        new CardNotActiveException("Card must be active for purchase"),
                        "Card not active",
                        409
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsConflictForInsufficientFunds",
                        new InsufficientFundsException(),
                        "Insufficient funds",
                        409
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsConflictForIdempotencyConflict",
                        new IdempotencyConflictException("request hash mismatch"),
                        "Idempotency Conflict",
                        409
                ),
                Arguments.argumentSet(
                        "makePaymentReturnsServiceUnavailableWhenFxProviderIsDown",
                        new FxProviderUnavailableException("Open Exchange Rates request failed"),
                        "Fx provider unavailable",
                        503
                )
        );
    }

    static Stream<Arguments> changeCardStatusServiceExceptionSource() {
        return Stream.of(
                Arguments.argumentSet(
                        "changeCardStatusReturnsNotFoundProblem",
                        CardStatus.ACTIVE,
                        new DebitCardNotFoundException("Debit card not found"),
                        "Debit card not found",
                        404
                ),
                Arguments.argumentSet(
                        "changeCardStatusReturnsBadRequestForUnsupportedStatus",
                        CardStatus.INACTIVE,
                        new UnsupportedCardStatusException("The entered status is not supported"),
                        "Unsupported card status",
                        400
                ),
                Arguments.argumentSet(
                        "changeCardStatusReturnsConflictForInvalidTransition",
                        CardStatus.FROZEN,
                        new InvalidCardStatusTransitionException("Only an active card can be frozen"),
                        "Invalid card status transition",
                        409
                ),
                Arguments.argumentSet(
                        "changeCardStatusReturnsConflictForExpiredCard",
                        CardStatus.ACTIVE,
                        new CardExpiredException("Expired cards cannot be activated"),
                        "Card expired",
                        409
                )
        );
    }
}
