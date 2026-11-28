package com.neobank.neobank.card.debit;

import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.CardStatus;
import com.neobank.neobank.card.debit.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.debit.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsResponse;
import com.neobank.neobank.card.limit.dto.CardSpendingLimitsUpdateRequest;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
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
import java.time.YearMonth;
import java.util.List;

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
    void getDebitCardReturnsCardForAuthenticatedCustomer() throws Exception {
        String email = "customer@example.com";

        DebitCardRetrieveResponse response = new DebitCardRetrieveResponse(
                "11111111111111111111111111111111",
                "1234",
                CardStatus.ACTIVE,
                YearMonth.of(2031, 9),
                "12345678900987",
                limitsResponse()
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
                "12345678900321",
                limitsResponse()
        );

        DebitCardRetrieveResponse secondResponse = new DebitCardRetrieveResponse(
                "22222222222222222222222222222222",
                "4321",
                CardStatus.FROZEN,
                YearMonth.of(2032, 10),
                "12300123456789",
                limitsResponse()
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
                "12345678900321",
                limitsResponse()
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

    @Test
    void updateSpendingLimitsReturnsUpdatedCardForAuthenticatedCustomer() throws Exception {
        String email = "customer@example.com";
        String cardReference = "1".repeat(32);
        CardSpendingLimitsUpdateRequest request = new CardSpendingLimitsUpdateRequest(new BigDecimal("500.00"), new BigDecimal("1500.00"));

        DebitCardRetrieveResponse response = new DebitCardRetrieveResponse(
                cardReference,
                "1234",
                CardStatus.ACTIVE,
                YearMonth.of(2031, 9),
                "12345678900321",
                new CardSpendingLimitsResponse(
                        new BigDecimal("500.00"),
                        new BigDecimal("1500.00"),
                        new BigDecimal("40.00"),
                        CurrencyCode.TRY
                )
        );

        given(debitCardService.updateSpendingLimits(cardReference, email, request))
                .willReturn(response);

        mockMvc.perform(put("/api/debit-cards/{cardReference}/spending-limits", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.spendingLimits.perTransactionLimit").value(500.00))
                .andExpect(jsonPath("$.spendingLimits.dailyLimit").value(1500.00))
                .andExpect(jsonPath("$.spendingLimits.spentToday").value(40.00))
                .andExpect(jsonPath("$.spendingLimits.currency").value("TRY"));

        verify(debitCardService).updateSpendingLimits(cardReference, email, request);
    }

    @Test
    void updateSpendingLimitsReturnsValidationProblemForMissingAndInvalidValues() throws Exception {
        String email = "customer@example.com";
        String cardReference = "1".repeat(32);

        mockMvc.perform(put("/api/debit-cards/{cardReference}/spending-limits", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyLimit\": 10.001}")
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.perTransactionLimit").value("Per-transaction limit cannot be null"))
                .andExpect(jsonPath("$.errors.dailyLimit").value("Daily limit must be a valid decimal number"));

        verifyNoInteractions(debitCardService);
    }

    @Test
    void updateSpendingLimitsReturnsValidationProblemForNonPositiveValues() throws Exception {
        String email = "customer@example.com";
        String cardReference = "1".repeat(32);

        mockMvc.perform(put("/api/debit-cards/{cardReference}/spending-limits", cardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CardSpendingLimitsUpdateRequest(new BigDecimal("-1.00"), BigDecimal.ZERO)))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.perTransactionLimit").value("Per-transaction limit must be a positive number"))
                .andExpect(jsonPath("$.errors.dailyLimit").value("Daily limit must be a positive number"));

        verifyNoInteractions(debitCardService);
    }

    @Test
    void updateSpendingLimitsRejectsInvalidCardReference() throws Exception {
        String invalidCardReference = "11zzz1111yy11xx1kkk111ggg11zzz11";

        mockMvc.perform(put("/api/debit-cards/{cardReference}/spending-limits", invalidCardReference)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CardSpendingLimitsUpdateRequest(new BigDecimal("100.00"), new BigDecimal("200.00"))))
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cardReference").value("Card reference must be 32 hexadecimal characters"));

        verifyNoInteractions(debitCardService);
    }

    @Test
    void updateSpendingLimitsReturnsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(put("/api/debit-cards/{cardReference}/spending-limits", "1".repeat(32))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CardSpendingLimitsUpdateRequest(new BigDecimal("100.00"), new BigDecimal("200.00")))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(debitCardService);
    }

    private static CardSpendingLimitsResponse limitsResponse() {
        return new CardSpendingLimitsResponse(
                new BigDecimal("1000.00"),
                new BigDecimal("2500.00"),
                new BigDecimal("0.00"),
                CurrencyCode.TRY
        );
    }
}
