package com.neobank.neobank.transaction;

import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.ProviderRateCache;
import com.neobank.neobank.shared.MySqlTestContainerConfiguration;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(MySqlTestContainerConfiguration.class)
public abstract class TransactionApiIntegrationTestSupport extends TransactionIntegrationTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected FxRateService fxRateService;

    @MockitoBean
    protected ProviderRateCache providerRateCache;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    protected ResultActions deposit(String accountNumber, DepositRequest request, String idempotencyKey, String email) throws Exception {
        return mockMvc.perform(post("/api/accounts/{accountNumber}/deposits", accountNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(email))));
    }

    protected ResultActions withdraw(String accountNumber, WithdrawalRequest request, String idempotencyKey, String email) throws Exception {
        return mockMvc.perform(post("/api/accounts/{accountNumber}/withdrawals", accountNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(email))));
    }

    protected ResultActions transfer(String sourceAccountNumber, TransferRequest request, String idempotencyKey, String email) throws Exception {
        return mockMvc.perform(post("/api/accounts/{sourceAccountNumber}/transfers", sourceAccountNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(email))));
    }

    protected ResultActions getAccount(String accountNumber, String email) throws Exception {
        return mockMvc.perform(get("/api/accounts/{accountNumber}", accountNumber)
                .with(jwt().jwt(jwt -> jwt.subject(email))));
    }
}
