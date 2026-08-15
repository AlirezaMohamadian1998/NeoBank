package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.CurrencyCode;
import com.neobank.neobank.account.InsufficientFundsException;
import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferResponse;
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

@WebMvcTest(TransferController.class)
@Import(SecurityConfig.class)
class TransferControllerTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void transferReturnsCreatedTransactionForAuthenticatedJwt() throws Exception {
        String email = "source@example.com";
        String sourceAccountNumber = "98765432100123";
        String targetAccountNumber = "12345678900987";
        
        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.00"),
                targetAccountNumber,
                "Test"
        );
        
        TransferResponse response = new TransferResponse(
                "7f3c8a21d9e64b5fa2c17e9084bd6a31",
                TransactionType.TRANSFER,
                sourceAccountNumber,
                targetAccountNumber,
                request.amount(),
                new BigDecimal("1000.00"),
                CurrencyCode.TRY,
                request.note(),
                Instant.parse("2026-08-15T12:00:00Z")
        );
        
        given(transferService.transfer(request, sourceAccountNumber, email))
                .willReturn(response);
        
        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transactionReference").value(response.transactionReference()))
                .andExpect(jsonPath("$.transactionType").value(response.transactionType().name()))
                .andExpect(jsonPath("$.sourceAccountNumber").value(response.sourceAccountNumber()))
                .andExpect(jsonPath("$.destinationAccountNumber").value(response.destinationAccountNumber()))
                .andExpect(jsonPath("$.amount").value(response.amount().doubleValue()))
                .andExpect(jsonPath("$.balanceAfter").value(response.balanceAfter().doubleValue()))
                .andExpect(jsonPath("$.currency").value(response.currency().name()))
                .andExpect(jsonPath("$.note").value(response.note()))
                .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()))
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath());

        verify(transferService).transfer(request, sourceAccountNumber, email);
    }

    @Test
    void transferReturnsValidationProblemForInvalidRequest() throws Exception {
        String email = "source@example.com";
        String sourceAccountNumber = "98765432100123";
        String targetAccountNumber = "12345678900987";

        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.000"),
                targetAccountNumber.repeat(2),
                "Test".repeat(100)
        );

        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/accounts/98765432100123/transfers"))
                .andExpect(jsonPath("$.errors.amount").value("Amount must be a valid decimal number"))
                .andExpect(jsonPath("$.errors.destinationAccountNumber").value("Account number must be exactly 14 digits"))
                .andExpect(jsonPath("$.errors.note").value("Note must not exceed 255 characters"));

        verifyNoInteractions(transferService);
    }

    @Test
    void transferReturnsUnauthorizedWithoutAuthentication() throws Exception {
        String sourceAccountNumber = "98765432100123";
        String targetAccountNumber = "12345678900987";

        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.00"),
                targetAccountNumber,
                "Test"
        );

        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transferService);
    }

    @Test
    void transferReturnsAccountNotFoundExceptionWhenAccountNotOwned() throws Exception {
        String email = "source@example.com";
        String sourceAccountNumber = "98765432100123";
        String targetAccountNumber = "12345678900987";

        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.00"),
                targetAccountNumber,
                "Test"
        );

        given(transferService.transfer(request, sourceAccountNumber, email))
                .willThrow(new AccountNotFoundException());

        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/98765432100123/transfers"))
                .andExpect(jsonPath("$.status").value(404));

        verify(transferService).transfer(request, sourceAccountNumber, email);
    }

    @Test
    void transferReturnsInsufficientBalanceExceptionWhenInsufficientBalance() throws Exception {
        String email = "source@example.com";
        String sourceAccountNumber = "98765432100123";
        String targetAccountNumber = "12345678900987";

        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.00"),
                targetAccountNumber,
                "Test"
        );

        given(transferService.transfer(request, sourceAccountNumber, email))
                .willThrow(new InsufficientFundsException());

        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.detail").value("Insufficient funds"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/98765432100123/transfers"))
                .andExpect(jsonPath("$.status").value(409));

        verify(transferService).transfer(request, sourceAccountNumber, email);
    }

    @Test
    void transferReturnsInvalidTransferExceptionWhenTransferToSourceAccount() throws Exception {
        String email = "source@example.com";
        String sourceAccountNumber = "98765432100123";

        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.00"),
                sourceAccountNumber,
                "Test"
        );

        given(transferService.transfer(request, sourceAccountNumber, email))
                .willThrow(new InvalidTransferException("Source and destination accounts cannot be the same"));

        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid transfer"))
                .andExpect(jsonPath("$.detail").value("Source and destination accounts cannot be the same"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/98765432100123/transfers"))
                .andExpect(jsonPath("$.status").value(400));

        verify(transferService).transfer(request, sourceAccountNumber, email);
    }

    @Test
    void transferReturnsInvalidTransferExceptionWhenCurrencyMismatch() throws Exception {
        String email = "source@example.com";
        String sourceAccountNumber = "98765432100123";
        String targetAccountNumber = "12345678900987";

        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.00"),
                targetAccountNumber,
                "Test"
        );

        given(transferService.transfer(request, sourceAccountNumber, email))
                .willThrow(new InvalidTransferException("Source and destination accounts must be in the same currency"));

        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid transfer"))
                .andExpect(jsonPath("$.detail").value("Source and destination accounts must be in the same currency"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/98765432100123/transfers"))
                .andExpect(jsonPath("$.status").value(400));

        verify(transferService).transfer(request, sourceAccountNumber, email);
    }

    @Test
    void transferReturnsAccountNotFoundExceptionWhenTargetDoesNotExist() throws Exception {
        String email = "source@example.com";
        String sourceAccountNumber = "98765432100123";
        String targetAccountNumber = "12345678900987";

        TransferRequest request = new TransferRequest(
                new BigDecimal("1000.00"),
                targetAccountNumber,
                "Test"
        );

        given(transferService.transfer(request, sourceAccountNumber, email))
                .willThrow(new AccountNotFoundException("Destination account not found"));

        mockMvc.perform(post("/api/accounts/{source}/transfers", sourceAccountNumber)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(jwt().jwt(jwt -> jwt.subject(email))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Account not found"))
                .andExpect(jsonPath("$.detail").value("Destination account not found"))
                .andExpect(jsonPath("$.instance").value("/api/accounts/98765432100123/transfers"))
                .andExpect(jsonPath("$.status").value(404));

        verify(transferService).transfer(request, sourceAccountNumber, email);
    }
}
