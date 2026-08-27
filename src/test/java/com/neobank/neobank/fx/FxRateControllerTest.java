package com.neobank.neobank.fx;

import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.fx.dto.SnapshotRateResponse;
import com.neobank.neobank.shared.money.CurrencyCode;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FxRateController.class)
@Import(SecurityConfig.class)
class FxRateControllerTest {

    @MockitoBean
    private FxRateService rateService;

    @MockitoBean
    private JwtDecoder decoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void authenticatedValidBaseReturnsExpectedResponse() throws Exception {
        SnapshotRateResponse response = new SnapshotRateResponse(
                "example:2026-08-27T12:00:00Z",
                Instant.parse("2026-08-27T13:00:00Z"),
                CurrencyCode.TRY,
                Map.of(
                        CurrencyCode.TRY, BigDecimal.ONE,
                        CurrencyCode.USD, new BigDecimal("0.02077275"),
                        CurrencyCode.GBP, new BigDecimal("0.01537183"),
                        CurrencyCode.EUR, new BigDecimal("0.01786456")
                )
        );

        given(rateService.getLatestRates(CurrencyCode.TRY))
                .willReturn(response);

        mockMvc.perform(get("/api/fx/rates")
                        .param("base", CurrencyCode.TRY.name())
                        .with(jwt().jwt(jwt -> jwt.subject("test-user"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(response)));
    }

    @Test
    void missingBaseReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/fx/rates")
                        .with(jwt().jwt(jwt -> jwt.subject("test-user"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(rateService);
    }

    @Test
    void invalidBaseReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/fx/rates")
                        .param("base", CurrencyCode.USD.name().toLowerCase())
                        .with(jwt().jwt(jwt -> jwt.subject("test-user"))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(rateService);
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/fx/rates")
                        .param("base", CurrencyCode.USD.name()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(rateService);
    }

    @Test
    void providerUnavailableReturnsServiceUnavailable() throws Exception {
        given(rateService.getLatestRates(any(CurrencyCode.class)))
                .willThrow(new FxProviderUnavailableException("Provider unavailable"));

        mockMvc.perform(get("/api/fx/rates")
                        .param("base", CurrencyCode.USD.name())
                        .with(jwt().jwt(jwt -> jwt.subject("test-user"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value("/api/fx/rates"))
                .andExpect(jsonPath("$.title").value("Fx provider unavailable"))
                .andExpect(jsonPath("$.detail").value("Provider unavailable"));
    }
}
