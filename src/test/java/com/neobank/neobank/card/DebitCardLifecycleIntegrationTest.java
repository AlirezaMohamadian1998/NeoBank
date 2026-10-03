package com.neobank.neobank.card;

import com.neobank.neobank.card.dto.DebitCardRetrieveResponse;
import com.neobank.neobank.card.support.DebitCardIntegrationTestSupport;
import com.neobank.neobank.customer.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DebitCardLifecycleIntegrationTest extends DebitCardIntegrationTestSupport {

    @Test
    void customerCanChangeOwnedCardStatusAndChangesArePersisted() throws Exception {
        String cardReference = "2".repeat(32);

        DebitCard debitCard = debitCardRepository.save(
                DebitCard.createNew(
                        cardReference,
                        "1234",
                        YearMonth.now(clock).plusYears(5),
                        YearMonth.now(clock),
                        account
                )
        );

        MvcResult firstMvcResult = mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.ACTIVE.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").isNotEmpty())
                .andExpect(jsonPath("$.lastFourDigits").isNotEmpty())
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.expirationYearMonth").isNotEmpty())
                .andExpect(jsonPath("$.fundingAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.id").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.fundingAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.customer").doesNotHaveJsonPath())
                .andReturn();

        DebitCardRetrieveResponse firstTransitionResponse =
                objectMapper.readValue(firstMvcResult.getResponse().getContentAsString(), DebitCardRetrieveResponse.class);

        assertThat(firstTransitionResponse.cardReference())
                .isEqualTo(cardReference);

        assertThat(firstTransitionResponse.lastFourDigits())
                .isEqualTo(debitCard.getLastFourDigits());

        assertThat(firstTransitionResponse.status())
                .isSameAs(CardStatus.ACTIVE);

        assertThat(firstTransitionResponse.expirationYearMonth())
                .isEqualTo(debitCard.getExpirationYearMonth());

        assertThat(firstTransitionResponse.fundingAccountNumber())
                .isEqualTo(debitCard.getFundingAccount().getAccountNumber());

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.ACTIVE.name()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()));

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.FROZEN.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()))
                .andExpect(jsonPath("$.status").value(CardStatus.FROZEN.name()));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.FROZEN.name()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()));

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.ACTIVE.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()))
                .andExpect(jsonPath("$.status").value(CardStatus.ACTIVE.name()));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.ACTIVE.name()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()));

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.BLOCKED.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()))
                .andExpect(jsonPath("$.status").value(CardStatus.BLOCKED.name()));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.BLOCKED.name()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()));

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.CLOSED.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()))
                .andExpect(jsonPath("$.status").value(CardStatus.CLOSED.name()));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.CLOSED.name()))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()));
    }

    @Test
    void customerCannotChangeAnotherCustomersCardStatus() throws Exception {
        String cardReference = "2".repeat(32);

        DebitCard debitCard = debitCardRepository.save(
                DebitCard.createNew(
                        cardReference,
                        "1234",
                        YearMonth.now(clock).plusYears(5),
                        YearMonth.now(clock),
                        account
                )
        );

        Customer secondCustomer = customerRepository.save(
                Customer.createNew(
                        "secondCustomer@example.com",
                        "{bcrypt}encoded-password",
                        "Lovelace Ada"
                )
        );

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.ACTIVE.name())
                        .with(jwt().jwt(jwt -> jwt.subject(secondCustomer.getEmail()))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Debit card not found"))
                .andExpect(jsonPath("$.detail").value("Debit card not found"))
                .andExpect(jsonPath("$.instance").value(
                        "/api/debit-cards/%s/status/%s".formatted(cardReference, CardStatus.ACTIVE.name())))
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.INACTIVE.name()));
    }

    @Test
    void changingMissingCardReturnsNotFound() throws Exception {
        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", "2".repeat(32), CardStatus.ACTIVE.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Debit card not found"))
                .andExpect(jsonPath("$.detail").value("Debit card not found"))
                .andExpect(jsonPath("$.instance").value(
                        "/api/debit-cards/%s/status/%s".formatted("2".repeat(32), CardStatus.ACTIVE.name())))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidTransitionLeavesPersistedStatusUnchanged() throws Exception {
        String cardReference = "2".repeat(32);

        YearMonth now = YearMonth.now(clock);

        DebitCard debitCard = debitCardRepository.save(
                DebitCard.createNew(
                        cardReference,
                        "1234",
                        now.plusYears(5),
                        now,
                        account
                )
        );

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.FROZEN.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid card status transition"))
                .andExpect(jsonPath("$.detail").value("Only active cards can be frozen"))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.INACTIVE.name()));
    }

    @Test
    void unsupportedTargetLeavesPersistedStatusUnchanged() throws Exception {
        String cardReference = "2".repeat(32);

        YearMonth now = YearMonth.now(clock);

        DebitCard debitCard = DebitCard.createNew(
                cardReference,
                "1234",
                now.plusYears(5),
                now,
                account
        );

        debitCard.activateCard(now);

        debitCardRepository.save(debitCard);

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.INACTIVE.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unsupported card status"))
                .andExpect(jsonPath("$.detail").value("Cards cannot be changed back to inactive"))
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.ACTIVE.name()));
    }

    @Test
    void expiredCardRejectsActivationWithoutChangingPersistedStatus() throws Exception {
        String cardReference = "2".repeat(32);
        YearMonth now = YearMonth.now(clock);

        DebitCard debitCard = DebitCard.createNew(
                cardReference,
                "1234",
                now.minusMonths(1),
                now.minusYears(5),
                account
        );

        debitCardRepository.save(debitCard);

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.ACTIVE.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Card expired"))
                .andExpect(jsonPath("$.detail").value("Expired cards cannot be activated"))
                .andExpect(jsonPath("$.status").value(409));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.INACTIVE.name()));
    }

    @Test
    void customerCanCloseExpiredCard() throws Exception {
        String cardReference = "2".repeat(32);

        DebitCard debitCard = debitCardRepository.save(
                DebitCard.createNew(
                        cardReference,
                        "1234",
                        YearMonth.of(2025, 9),
                        YearMonth.of(2020, 9),
                        account
                )
        );

        mockMvc.perform(patch("/api/debit-cards/{cardReference}/status/{status}", cardReference, CardStatus.CLOSED.name())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.fundingAccountNumber").value(debitCard.getFundingAccount().getAccountNumber()))
                .andExpect(jsonPath("$.status").value(CardStatus.CLOSED.name()));

        mockMvc.perform(get("/api/debit-cards/{cardReference}", cardReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.cardReference").value(cardReference))
                .andExpect(jsonPath("$.status").value(CardStatus.CLOSED.name()));
    }
}

