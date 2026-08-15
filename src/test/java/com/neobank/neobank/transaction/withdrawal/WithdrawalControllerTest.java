package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.CurrencyCode;
import com.neobank.neobank.account.InsufficientFundsException;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
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

@WebMvcTest(WithdrawalController.class)
@Import(SecurityConfig.class)
class WithdrawalControllerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WithdrawalService withdrawalService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void withdrawReturnsCreatedTransactionForAuthenticatedJwt() throws Exception {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("150.00"),
                "Test"
        );

        WithdrawalResponse response = new WithdrawalResponse(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                TransactionType.WITHDRAWAL,
                accountNumber,
                new BigDecimal("150.00"),
                CurrencyCode.TRY,
                new BigDecimal("100.00"),
                "Test",
                Instant.parse("2026-08-15T12:00:00Z")
        );

        given(withdrawalService.withdraw(request, accountNumber, email))
                .willReturn(response);

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", accountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").value(response.transactionReference()))
                .andExpect(jsonPath("$.transactionType").value(response.transactionType().name()))
                .andExpect((jsonPath("$.accountNumber").value(accountNumber)))
                .andExpect(jsonPath("$.amount").value(response.amount().doubleValue()))
                .andExpect(jsonPath("$.currency").value(response.currency().name()))
                .andExpect(jsonPath("$.balanceAfter").value(response.balanceAfter().doubleValue()))
                .andExpect(jsonPath("$.note").value(response.note()))
                .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()))
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath());

        verify(withdrawalService).withdraw(request, accountNumber, email);
    }

    @Test
    void withdrawReturnsValidationProblemForInvalidRequest() throws Exception {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("-1000.00"),
                "Test".repeat(100)
        );

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", accountNumber)
                         .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.amount").value("Amount must be a positive number"))
                .andExpect(jsonPath("$.errors.note").value("Note must not exceed 255 characters"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/withdrawals"))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(withdrawalService);
    }

    @Test
    void withdrawReturnsAccountNotFoundExceptionWhenAccountNotOwned() throws Exception {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        given(withdrawalService.withdraw(request, accountNumber, email))
                .willThrow(new AccountNotFoundException());

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", accountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/withdrawals"))
                .andExpect(jsonPath("$.status").value(404));

        verify(withdrawalService).withdraw(request, accountNumber, email);
    }

    @Test
    void withdrawReturnsUnauthorizedWithoutAuthentication() throws Exception {
        String accountNumber = "12345678900987";

        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", accountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(withdrawalService);
    }

    @Test
    void withdrawReturnsInsufficientFundsWhenBalanceIsLessThanAmount() throws Exception {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        given(withdrawalService.withdraw(request, accountNumber, email))
                .willThrow(new InsufficientFundsException());

        mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", accountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.detail").value("Insufficient funds"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/12345678900987/withdrawals"))
                .andExpect(jsonPath("$.status").value(409));

        verify(withdrawalService).withdraw(request, accountNumber, email);
    }
}
