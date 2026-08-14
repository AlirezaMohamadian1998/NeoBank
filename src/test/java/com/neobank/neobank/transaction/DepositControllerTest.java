package com.neobank.neobank.transaction;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.CurrencyCode;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.transaction.dto.DepositRequest;
import com.neobank.neobank.transaction.dto.DepositResponse;
import org.junit.jupiter.api.Test;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DepositController.class)
@Import(SecurityConfig.class)
class DepositControllerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepositService depositService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void depositReturnsCreatedTransactionForAuthenticatedJwt() throws Exception {
        String email = "customer@example.com";
        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        DepositResponse response = new DepositResponse(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                TransactionType.DEPOSIT,
                "12345678900321",
                new BigDecimal("1000.00"),
                CurrencyCode.TRY,
                new BigDecimal("1000.00"),
                "Test",
                Instant.parse("2026-08-14T12:00:00Z")
        );

        given(depositService.deposit(request, response.accountNumber(), email))
                .willReturn(response);

        mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", response.accountNumber())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(response)))
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath());

        verify(depositService).deposit(request, response.accountNumber(), email);
    }

    @Test
    void depositReturnsNotFoundWhenAccountIsNotOwned() throws Exception {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        given(depositService.deposit(request, accountNumber, email))
                .willThrow(new AccountNotFoundException());

        mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", accountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900321/deposits"))
                .andExpect(jsonPath("$.status").value(404));

        verify(depositService).deposit(request, accountNumber, email);
    }

    @Test
    void depositReturnsValidationProblemForInvalidRequest() throws Exception {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        DepositRequest request = new DepositRequest(
                new BigDecimal("-1000.00"),
                "Test".repeat(100)
        );

        mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", accountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.amount").value("Amount must be a positive number"))
                .andExpect(jsonPath("$.errors.note").value("Note must not exceed 255 characters"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900321/deposits"))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(depositService);
    }

    @Test
    void depositReturnsUnauthorizedWithoutAuthentication() throws Exception {
        String accountNumber = "12345678900321";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", accountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(depositService);
    }
}
