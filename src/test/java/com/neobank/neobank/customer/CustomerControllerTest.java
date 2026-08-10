package com.neobank.neobank.customer;

import com.neobank.neobank.auth.SecurityConfig;
import com.neobank.neobank.customer.dto.CustomerResponse;
import com.neobank.neobank.customer.dto.RegisterCustomerRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
@Import(SecurityConfig.class)
class CustomerControllerTest {

    private static final String REGISTRATION_ENDPOINT = "/api/customers";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void registerReturnsCreatedCustomerForValidRequestWithoutAuthenticationOrCsrf() throws Exception {
        RegisterCustomerRequest request = new RegisterCustomerRequest(
                "customer@example.com",
                "raw-password-123",
                "Ada Lovelace"
        );
        CustomerResponse response = new CustomerResponse(
                "customer@example.com",
                "Ada Lovelace",
                Instant.parse("2026-08-09T12:00:00Z")
        );

        given(customerService.register(request)).willReturn(response);

        mockMvc.perform(post(REGISTRATION_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.email").value(response.email()))
                .andExpect(jsonPath("$.fullName").value(response.fullName()))
                .andExpect(jsonPath("$.createdAt").value(response.createdAt().toString()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(cookie().doesNotExist("JSESSIONID"));

        verify(customerService).register(request);
    }

    @Test
    void registerReturnsProblemDetailForInvalidRequestWithoutExposingPassword() throws Exception {
        String rawPassword = "too-short";
        RegisterCustomerRequest request = new RegisterCustomerRequest(
                "not-an-email",
                rawPassword,
                " "
        );

        mockMvc.perform(post(REGISTRATION_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Validation failed for one or more fields."))
                .andExpect(jsonPath("$.instance").value(REGISTRATION_ENDPOINT))
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.fullName").exists())
                .andExpect(content().string(not(containsString(rawPassword))));

        verifyNoInteractions(customerService);
    }

    @Test
    void registerReturnsConflictProblemDetailWhenEmailAlreadyExists() throws Exception {
        RegisterCustomerRequest request = new RegisterCustomerRequest(
                "customer@example.com",
                "raw-password-123",
                "Ada Lovelace"
        );

        given(customerService.register(request))
                .willThrow(new EmailAlreadyRegisteredException("Email already registered"));

        mockMvc.perform(post(REGISTRATION_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Email already registered"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Email already registered"))
                .andExpect(jsonPath("$.instance").value(REGISTRATION_ENDPOINT));

        verify(customerService).register(request);
    }

    @Test
    void getCustomersIsProtectedForAnonymousRequests() throws Exception {
        mockMvc.perform(get(REGISTRATION_ENDPOINT))
                .andExpect(status().isForbidden());

        verifyNoInteractions(customerService);
    }
}
