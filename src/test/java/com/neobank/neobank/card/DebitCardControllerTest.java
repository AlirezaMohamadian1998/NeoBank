package com.neobank.neobank.card;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.card.dto.DebitCardIssueResponse;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
