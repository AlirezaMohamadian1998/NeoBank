package com.neobank.neobank.card.payment;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.payment.dto.CardRefundResponse;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CardRefundController.class)
@Import(SecurityConfig.class)
class CardRefundControllerTest {

    private static final String URL = "/api/accounts/{merchantAccountNumber}/card-refunds/{purchaseReference}";
    private static final String MERCHANT_ACCOUNT_NUMBER = "98".repeat(7);
    private static final String PURCHASE_REFERENCE = "6".repeat(32);
    private static final String IDEMPOTENCY_KEY = "1".repeat(32);
    private static final String EMAIL = "merchant@example.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CardRefundService cardRefundService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void refundCardPurchaseReturnsCreatedRefund() throws Exception {
        CardRefundResponse response = new CardRefundResponse(
                "a".repeat(32),
                PURCHASE_REFERENCE,
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                new BigDecimal("0.00"),
                "Test merchant",
                MERCHANT_ACCOUNT_NUMBER,
                "1234",
                Instant.parse("2026-11-18T12:00:00Z")
        );

        given(cardRefundService.refundPurchase(MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, EMAIL))
                .willReturn(response);

        mockMvc.perform(post(URL, MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.refundReference").value("a".repeat(32)))
                .andExpect(jsonPath("$.purchaseReference").value(PURCHASE_REFERENCE))
                .andExpect(jsonPath("$.requestedAmount").value(250.00))
                .andExpect(jsonPath("$.requestedCurrency").value("TRY"))
                .andExpect(jsonPath("$.debitedAmount").value(250.00))
                .andExpect(jsonPath("$.debitedCurrency").value("TRY"))
                .andExpect(jsonPath("$.balanceAfter").value(0.00))
                .andExpect(jsonPath("$.merchantName").value("Test merchant"))
                .andExpect(jsonPath("$.merchantAccountNumber").value(MERCHANT_ACCOUNT_NUMBER))
                .andExpect(jsonPath("$.cardLastFourDigits").value("1234"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-18T12:00:00Z"))
                .andExpect(jsonPath("$.fundingAccountNumber").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.cardReference").doesNotHaveJsonPath());
    }

    @Test
    void refundCardPurchaseReturnsValidationProblemForInvalidPathVariables() throws Exception {
        mockMvc.perform(post(URL, "123", "NOT-A-REFERENCE")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.merchantAccountNumber").value("Account number must be exactly 14 digits"))
                .andExpect(jsonPath("$.errors.purchaseReference").value("Purchase reference must be 32 hexadecimal characters"));

        verifyNoInteractions(cardRefundService);
    }

    @Test
    void refundCardPurchaseReturnsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(post(URL, MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(cardRefundService);
    }

    @Test
    void refundCardPurchaseReturnsBadRequestWhenIdempotencyKeyHeaderIsMissing() throws Exception {
        mockMvc.perform(post(URL, MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(cardRefundService);
    }

    @Test
    void refundCardPurchaseReturnsNotFoundForUnknownOrForeignPurchase() throws Exception {
        given(cardRefundService.refundPurchase(MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, EMAIL))
                .willThrow(new CardPurchaseNotFoundException("Card purchase not found"));

        mockMvc.perform(post(URL, MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card purchase not found"))
                .andExpect(jsonPath("$.detail").value("Card purchase not found"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void refundCardPurchaseReturnsNotFoundWhenMerchantAccountIsNotOwned() throws Exception {
        given(cardRefundService.refundPurchase(MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, EMAIL))
                .willThrow(new AccountNotFoundException("Merchant's account not found"));

        mockMvc.perform(post(URL, MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isNotFound());
    }

    @Test
    void refundCardPurchaseReturnsConflictWhenAlreadyRefunded() throws Exception {
        given(cardRefundService.refundPurchase(MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, EMAIL))
                .willThrow(new CardPurchaseNotRefundableException("Card purchase is already refunded"));

        mockMvc.perform(post(URL, MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card purchase not refundable"))
                .andExpect(jsonPath("$.detail").value("Card purchase is already refunded"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void refundCardPurchaseReturnsInsufficientFundsProblemWhenMerchantCannotCoverRefund() throws Exception {
        given(cardRefundService.refundPurchase(MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE, IDEMPOTENCY_KEY, EMAIL))
                .willThrow(new InsufficientFundsException());

        mockMvc.perform(post(URL, MERCHANT_ACCOUNT_NUMBER, PURCHASE_REFERENCE)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .with(jwt().jwt(jwt -> jwt.subject(EMAIL))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Insufficient funds"));
    }
}
