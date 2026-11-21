package com.neobank.neobank.card;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CardPurchaseResponse;
import com.neobank.neobank.card.exception.CardExpiredException;
import com.neobank.neobank.card.exception.CardNotActiveException;
import com.neobank.neobank.card.exception.CardNotFoundException;
import com.neobank.neobank.card.exception.InvalidCardPaymentException;
import com.neobank.neobank.creditaccount.CreditLimitExceededException;
import com.neobank.neobank.fx.FxProviderUnavailableException;
import com.neobank.neobank.idempotency.IdempotencyConflictException;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

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

        mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
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

        mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
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

        mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
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

        mockMvc.perform(post("/api/cards/{cardReference}/payments", invalidCardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
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

        mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().is(httpStatusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(ex.getMessage()))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.instance").value("/api/cards/%s/payments".formatted(cardReference)))
                .andExpect(jsonPath("$.status").value(httpStatusCode));

        verify(cardPaymentService).makePurchase(cardReference, request, idempotencyKey, email);
    }

    static Stream<Arguments> makePaymentServiceExceptionSource() {
        return Stream.of(
                Arguments.argumentSet(
                        "makePaymentReturnsNotFoundForMissingCard",
                        new CardNotFoundException("Card not found"),
                        "Card not found",
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
                        "makePaymentReturnsConflictWhenCreditLimitIsExceeded",
                        new CreditLimitExceededException("Amount exceeds the available credit of 200.00"),
                        "Credit limit exceeded",
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
}
