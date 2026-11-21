package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.CardPurchaseRequest;
import com.neobank.neobank.card.dto.CreditCardIssueRequest;
import com.neobank.neobank.card.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.shared.money.CurrencyCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CreditCardIntegrationTest extends DebitCardIntegrationTestSupport {

    @Test
    void issuingTwiceWithSameIdempotencyKeyCreatesOneCreditCardAndAccount() throws Exception {
        CreditCardIssueResponse first = issueCreditCard(CurrencyCode.TRY, "1".repeat(32));
        CreditCardIssueResponse replay = issueCreditCard(CurrencyCode.TRY, "1".repeat(32));

        assertThat(replay).isEqualTo(first);
        assertThat(first.creditAccountNumber()).matches("[0-9]{14}");
        assertThat(first.creditLimit()).isEqualByComparingTo("150000.00");
        assertThat(creditAccountRepository.count()).isEqualTo(1);
        assertThat(cardRepository.count()).isEqualTo(1);
    }

    @Test
    void creditCardCanBeChargedUpToItsCreditLimit() throws Exception {
        CreditCardIssueResponse issued = issueCreditCard(CurrencyCode.TRY, "1".repeat(32));

        mockMvc.perform(patch("/api/credit-cards/{cardReference}/status/{status}", issued.cardReference(), "ACTIVE")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        purchase(issued.cardReference(), "100000.00", "2".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedAccountNumber").value(issued.creditAccountNumber()))
                .andExpect(jsonPath("$.balanceAfter").value(100000.00));

        purchase(issued.cardReference(), "50000.01", "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Credit limit exceeded"))
                .andExpect(jsonPath("$.detail").value("Amount exceeds the available credit of 50000.00"));

        purchase(issued.cardReference(), "50000.00", "4".repeat(32))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/credit-cards/{cardReference}", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150000.00))
                .andExpect(jsonPath("$.availableCredit").value(0.00))
                .andExpect(jsonPath("$.spendingLimits.spentToday").value(150000.00));

        assertThat(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance())
                .isEqualByComparingTo("150000.00");
    }

    @Test
    void customerCannotSeeAnotherCustomersCreditCard() throws Exception {
        CreditCardIssueResponse issued = issueCreditCard(CurrencyCode.TRY, "1".repeat(32));

        mockMvc.perform(get("/api/credit-cards/{cardReference}", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject("other@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit card not found"));
    }

    private CreditCardIssueResponse issueCreditCard(CurrencyCode currency, String idempotencyKey) throws Exception {
        String body = mockMvc.perform(post("/api/credit-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreditCardIssueRequest(currency)))
                        .header("Idempotency-Key", idempotencyKey)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readValue(body, CreditCardIssueResponse.class);
    }

    private ResultActions purchase(String cardReference, String amount, String idempotencyKey) throws Exception {
        CardPurchaseRequest request = new CardPurchaseRequest(
                account.getAccountNumber(),
                CurrencyCode.TRY,
                new BigDecimal(amount),
                null,
                "Test merchant"
        );

        return mockMvc.perform(post("/api/cards/{cardReference}/payments", cardReference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))));
    }
}
