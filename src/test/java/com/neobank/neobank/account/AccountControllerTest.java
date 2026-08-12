package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.AccountResponse;
import com.neobank.neobank.account.dto.CreateAccountRequest;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.customer.CustomerNotFoundException;
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

@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {
    private static final String ACCOUNT_ENDPOINT = "/api/accounts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void createAccountReturnsCreatedAccountForAuthenticatedJwt() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                "  Private Account  ",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        AccountResponse response = new AccountResponse(
                "12345678900987",
                request.name().trim(),
                request.accountType(),
                request.currency(),
                BigDecimal.ZERO.setScale(2),
                Instant.parse("2026-08-12T12:00:00Z")
        );

        String email = "customer@example.com";

        given(accountService.createAccount(request, email))
                .willReturn(response);

        mockMvc.perform(post(ACCOUNT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("Private Account"))
                .andExpect(jsonPath("$.accountNumber").value("12345678900987"))
                .andExpect(jsonPath("$.accountType").value(AccountType.CURRENT.name()))
                .andExpect(jsonPath("$.currency").value(CurrencyCode.TRY.name()))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.createdAt").value("2026-08-12T12:00:00Z"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.customer").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist())
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        verify(accountService).createAccount(request, email);
    }

    @Test
    void createAccountReturnsUnauthorizedWithoutAuthentication() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        mockMvc.perform(post(ACCOUNT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountService);
    }

    @Test
    void createAccountReturnsProblemDetailForInvalidRequest() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                "Private Account".repeat(10),
                null,
                null
        );

        mockMvc.perform(post(ACCOUNT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value(ACCOUNT_ENDPOINT))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.accountType").exists())
                .andExpect(jsonPath("$.errors.currency").exists());

        verifyNoInteractions(accountService);
    }

    @Test
    void createAccountReturnsNotFoundWhenAuthenticatedCustomerDoesNotExist() throws Exception {
        CreateAccountRequest request = new CreateAccountRequest(
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        String email = "customer@example.com";

        given(accountService.createAccount(request, email))
                .willThrow(new CustomerNotFoundException("Customer not found"));

        mockMvc.perform(post(ACCOUNT_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)).with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Customer not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Customer not found"))
                .andExpect(jsonPath("$.instance").value(ACCOUNT_ENDPOINT));

        verify(accountService).createAccount(request, email);
    }
}
