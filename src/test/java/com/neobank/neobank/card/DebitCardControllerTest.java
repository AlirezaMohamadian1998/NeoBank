package com.neobank.neobank.card;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
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

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DebitCardController.class)
@Import(SecurityConfig.class)
class DebitCardControllerTest {

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
}
