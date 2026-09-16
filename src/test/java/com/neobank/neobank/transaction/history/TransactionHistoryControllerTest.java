package com.neobank.neobank.transaction.history;

import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryFilter;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.*;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TransactionHistoryController.class)
@Import(SecurityConfig.class)
class TransactionHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TransactionHistoryService transactionHistoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void historyReturnsPagedResponseForAuthenticatedCustomer() throws Exception {
        Page<TransactionHistoryResponse> page = new PageImpl<>(
                List.of(new TransactionHistoryResponse(
                        "12345678900987",
                        "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                        TransactionType.DEPOSIT,
                        EntryDirection.CREDIT,
                        new BigDecimal("4000.00"),
                        CurrencyCode.TRY,
                        new BigDecimal("100.00"),
                        CurrencyCode.USD,
                        new BigDecimal("5000.00"),
                        Instant.parse("2026-09-01T12:00:00Z")
                )),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"))),
                1
        );

        given(transactionHistoryService.getHistoryByAccount(any(), eq("customer@example.com"), any()))
                .willReturn(page);

        PagedModel<TransactionHistoryResponse> expected = new PagedModel<>(page);

        mockMvc.perform(get("/api/transactions")
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(expected)));
    }

    @Test
    void historyBindsFiltersAndPagination() throws Exception {
        ArgumentCaptor<TransactionHistoryFilter> filterCaptor = ArgumentCaptor.forClass(TransactionHistoryFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        given(transactionHistoryService.getHistoryByAccount(any(), anyString(), any()))
                .willReturn(Page.empty());

        mockMvc.perform(get("/api/transactions")
                        .param("accountNumber", "12345678900987")
                        .param("requestedCurrency", "USD")
                        .param("type", "TRANSFER")
                        .param("appliedMin", "100.00")
                        .param("appliedMax", "5000.00")
                        .param("dateFrom", "2026-08-01T00:00:00Z")
                        .param("dateTo", "2026-09-01T23:59:59Z")
                        .param("page", "2")
                        .param("size", "25")
                        .param("sort", "appliedAmount,asc")
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isOk());

        verify(transactionHistoryService)
                .getHistoryByAccount(filterCaptor.capture(), eq("customer@example.com"), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        TransactionHistoryFilter filter = filterCaptor.getValue();

        assertThat(pageable.getSort())
                .containsExactly(Sort.Order.asc("appliedAmount"));

        assertThat(pageable.getPageNumber())
                .isEqualTo(2);

        assertThat(pageable.getPageSize())
                .isEqualTo(25);

        assertThat(pageable.isPaged())
                .isTrue();

        assertThat(filter.accountNumber())
                .isEqualTo("12345678900987");

        assertThat(filter.requestedCurrency())
                .isSameAs(CurrencyCode.USD);

        assertThat(filter.type())
                .isSameAs(TransactionType.TRANSFER);

        assertThat(filter.appliedMin())
                .isEqualByComparingTo("100.00");

        assertThat(filter.appliedMax())
                .isEqualByComparingTo("5000.00");

        assertThat(filter.dateFrom())
                .isEqualTo("2026-08-01T00:00:00Z");

        assertThat(filter.dateTo())
                .isEqualTo("2026-09-01T23:59:59Z");
    }

    @Test
    void historyReturnsUnauthorizedWithoutAuthentication() throws Exception {

        mockMvc.perform(get("/api/transactions")
                        .param("accountNumber", "12345678900987")
                        .param("requestedCurrency", "USD")
                        .param("type", "TRANSFER")
                        .param("appliedMin", "100.00")
                        .param("appliedMax", "5000.00")
                        .param("dateFrom", "2026-08-01T00:00:00Z")
                        .param("dateTo", "2026-09-01T23:59:59Z")
                        .param("page", "2")
                        .param("size", "25")
                        .param("sort", "appliedAmount,asc"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(transactionHistoryService);
    }

    @Test
    void historyRejectsInvalidFilters() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("accountNumber", "123")
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transactionHistoryService);
    }

    @ParameterizedTest
    @MethodSource("malformedQueryParameters")
    void historyRejectsMalformedQueryParameters(Map<String, String> params) throws Exception {

        MockHttpServletRequestBuilder request = get("/api/transactions")
                .with(jwt().jwt(jwt -> jwt.subject("customer@example.com")));

        params.forEach((name, value) -> request.param(name, value));

        mockMvc.perform(request)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transactionHistoryService);
    }

    @Test
    void defaultsToPublicCreatedAtSort() throws Exception {
        given(transactionHistoryService.getHistoryByAccount(any(), eq("customer@example.com"), any()))
                .willReturn(Page.empty());

        mockMvc.perform(get("/api/transactions")
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isOk());

        verify(transactionHistoryService).getHistoryByAccount(
                any(TransactionHistoryFilter.class),
                eq("customer@example.com"),
                argThat(pageable -> pageable.getSort().equals(Sort.by(Sort.Order.desc("createdAt"))))
        );
    }

    @Test
    void invalidHistorySortReturnsBadRequestProblem() throws Exception {
        given(transactionHistoryService.getHistoryByAccount(any(), eq("customer@example.com"), any(Pageable.class)))
                .willThrow(new InvalidHistorySortException("Supported sort fields are createdAt and appliedAmount."));

        mockMvc.perform(get("/api/transactions")
                        .param("sort", "unknown,desc")
                        .with(jwt().jwt(jwt -> jwt.subject("customer@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid history sort"))
                .andExpect(jsonPath("$.detail").value("Supported sort fields are createdAt and appliedAmount."))
                .andExpect(jsonPath("$.instance").value("/api/transactions"));
    }

    static Stream<Arguments> malformedQueryParameters() {
        return Stream.of(
                Arguments.of(Map.of(
                        "type", "depo"
                )),

                Arguments.of(Map.of(
                        "requestedCurrency", "US Dollar"
                )),

                Arguments.of(Map.of(
                        "appliedMin", "US"
                )),

                Arguments.of(Map.of(
                        "appliedMax", "Dollar"
                )),

                Arguments.of(Map.of(
                        "dateFrom", "Now"
                )),

                Arguments.of(Map.of(
                        "dateTo", "Past"
                ))
        );
    }
}
