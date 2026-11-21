package com.neobank.neobank.card;

import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.dto.CardSpendingLimitsResponse;
import com.neobank.neobank.card.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.dto.CreditCardRetrieveResponse;
import com.neobank.neobank.card.exception.CreditCardNotFoundException;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreditCardService creditCardService;

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
