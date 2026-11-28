package com.neobank.neobank.card.credit;

import com.neobank.neobank.card.credit.dto.CreditCardIssueResponse;
import com.neobank.neobank.card.credit.dto.CreditCardPayoutRequest;
import com.neobank.neobank.card.credit.dto.CreditCardPayoutResponse;
import com.neobank.neobank.card.credit.dto.CreditCardRepaymentResponse;
import com.neobank.neobank.card.payment.dto.CardPurchaseRequest;
import com.neobank.neobank.card.payment.dto.CardPurchaseResponse;
import com.neobank.neobank.card.support.CardIntegrationTestSupport;
import com.neobank.neobank.creditaccount.CreditAccount;
import com.neobank.neobank.creditaccount.CreditStatementService;
import com.neobank.neobank.fx.FxProviderRates;
import com.neobank.neobank.fx.FxRateService;
import com.neobank.neobank.fx.ProviderRateCache;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CreditCardIntegrationTest extends CardIntegrationTestSupport {

    @MockitoBean
    private ProviderRateCache providerRateCache;

    @Autowired
    private FxRateService fxRateService;

    @Autowired
    private CreditStatementService creditStatementService;

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

        purchase(issued.cardReference(), account.getAccountNumber(), "100000.00", CurrencyCode.TRY, "2".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.linkedAccountNumber").value(issued.creditAccountNumber()))
                .andExpect(jsonPath("$.balanceAfter").value(100000.00));

        purchase(issued.cardReference(), account.getAccountNumber(), "50000.01", CurrencyCode.TRY, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Credit limit exceeded"))
                .andExpect(jsonPath("$.detail").value("Amount exceeds the available credit of 50000.00"));

        purchase(issued.cardReference(), account.getAccountNumber(), "50000.00", CurrencyCode.TRY, "4".repeat(32))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/credit-cards/{cardReference}", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(150000.00))
                .andExpect(jsonPath("$.availableCredit").value(0.00))
                .andExpect(jsonPath("$.spendingLimits.spentToday").value(150000.00));

        assertThat(balanceOf(account.getAccountNumber()))
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

    @Test
    void ownerCanListAndGetTheStatementsOfTheirCard() throws Exception {
        CreditCardIssueResponse issued = issueCreditCard(CurrencyCode.TRY, "1".repeat(32));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        CreditAccount creditAccount = generateFirstStatement(issued.cardReference());
        String statementReference = creditStatementRepository.findAll().getFirst().getStatementReference();

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].statementReference").value(statementReference));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}", issued.cardReference(), statementReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statementDate").value(creditAccount.getNextStatementDate().toString()))
                .andExpect(jsonPath("$.closingBalance").value(0.00))
                .andExpect(jsonPath("$.interestCharged").value(0.00))
                .andExpect(jsonPath("$.currency").value("TRY"));
    }

    @Test
    void customerCannotSeeAnotherCustomersStatements() throws Exception {
        CreditCardIssueResponse issued = issueCreditCard(CurrencyCode.TRY, "1".repeat(32));
        generateFirstStatement(issued.cardReference());
        String statementReference = creditStatementRepository.findAll().getFirst().getStatementReference();

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject("other@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit card not found"));

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}", issued.cardReference(), statementReference)
                        .with(jwt().jwt(jwt -> jwt.subject("other@example.com"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit card not found"));
    }

    @Test
    void statementOfAnotherCardIsNotFound() throws Exception {
        CreditCardIssueResponse first = issueCreditCard(CurrencyCode.TRY, "1".repeat(32));
        CreditCardIssueResponse second = issueCreditCard(CurrencyCode.TRY, "2".repeat(32));
        generateFirstStatement(first.cardReference());
        String statementReference = creditStatementRepository.findAll().getFirst().getStatementReference();

        mockMvc.perform(get("/api/credit-cards/{cardReference}/statements/{statementReference}", second.cardReference(), statementReference)
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Credit statement not found"));
    }

    @Test
    void repaymentFreesUpCreditAndShowsInTheSourceAccountHistory() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));

        purchase(issued.cardReference(), account.getAccountNumber(), "150000.00", CurrencyCode.TRY, "2".repeat(32))
                .andExpect(status().isCreated());

        purchase(issued.cardReference(), account.getAccountNumber(), "0.01", CurrencyCode.TRY, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Credit limit exceeded"));

        repay(issued.cardReference(), "50000.00", null, "4".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditAccountNumber").value(issued.creditAccountNumber()))
                .andExpect(jsonPath("$.sourceAccountNumber").value(account.getAccountNumber()))
                .andExpect(jsonPath("$.debitedAmount").value(50000.00))
                .andExpect(jsonPath("$.debitedCurrency").value("TRY"))
                .andExpect(jsonPath("$.creditedAmount").value(50000.00))
                .andExpect(jsonPath("$.creditedCurrency").value("TRY"))
                .andExpect(jsonPath("$.sourceAccountBalanceAfter").value(100000.00))
                .andExpect(jsonPath("$.creditCardBalanceAfter").value(100000.00));

        mockMvc.perform(get("/api/credit-cards/{cardReference}", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100000.00))
                .andExpect(jsonPath("$.availableCredit").value(50000.00));

        mockMvc.perform(get("/api/transactions")
                        .param("type", "CREDIT_CARD_REPAYMENT")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].accountNumber").value(account.getAccountNumber()))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].appliedAmount").value(50000.00))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(100000.00));
    }

    @Test
    void repayingTwiceWithSameIdempotencyKeyRepaysOnce() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));

        purchase(issued.cardReference(), account.getAccountNumber(), "1000.00", CurrencyCode.TRY, "2".repeat(32))
                .andExpect(status().isCreated());

        CreditCardRepaymentResponse first = objectMapper.readValue(
                repay(issued.cardReference(), "400.00", null, "3".repeat(32))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                CreditCardRepaymentResponse.class
        );

        repay(issued.cardReference(), "400.00", null, "3".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionReference").value(first.transactionReference()))
                .andExpect(jsonPath("$.sourceAccountBalanceAfter").value(600.00))
                .andExpect(jsonPath("$.creditCardBalanceAfter").value(600.00));

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("600.00");
    }

    @Test
    void repaymentOverTheOutstandingBalanceIsRejected() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));

        purchase(issued.cardReference(), account.getAccountNumber(), "1000.00", CurrencyCode.TRY, "2".repeat(32))
                .andExpect(status().isCreated());

        repay(issued.cardReference(), "1000.01", null, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Repayment exceeds balance"))
                .andExpect(jsonPath("$.detail").value("Repayment exceeds the outstanding balance of 1000.00"));

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void refundAfterFullRepaymentLeavesACreditBalance() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));

        String purchaseReference = objectMapper.readValue(
                purchase(issued.cardReference(), account.getAccountNumber(), "1000.00", CurrencyCode.TRY, "2".repeat(32))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                CardPurchaseResponse.class
        ).transactionReference();

        repay(issued.cardReference(), "1000.00", null, "3".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditCardBalanceAfter").value(0.00));

        LedgerAccount merchantLedgerAccount =
                accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getLedgerAccount();
        merchantLedgerAccount.credit(new BigDecimal("1000.00"));
        ledgerAccountRepository.save(merchantLedgerAccount);

        refund(account.getAccountNumber(), purchaseReference, "4".repeat(32), customer.getEmail())
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/credit-cards/{cardReference}", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(-1000.00))
                .andExpect(jsonPath("$.availableCredit").value(151000.00));

        repay(issued.cardReference(), "0.01", null, "5".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Repayment exceeds the outstanding balance of 0.00"));
    }

    @Test
    void repaymentFromAccountInAnotherCurrencyUsesTheLockedRate() throws Exception {
        given(providerRateCache.getLatestRates()).willReturn(new FxProviderRates(
                "test-provider",
                CurrencyCode.USD,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.EUR, new BigDecimal("0.85"),
                        CurrencyCode.GBP, new BigDecimal("0.75"),
                        CurrencyCode.TRY, new BigDecimal("40.00")
                ),
                Instant.now()
        ));

        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.USD, "1".repeat(32));

        mockMvc.perform(post("/api/cards/{cardReference}/payments", issued.cardReference())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CardPurchaseRequest(
                                account.getAccountNumber(),
                                CurrencyCode.USD,
                                new BigDecimal("100.00"),
                                null,
                                "Test merchant"
                        )))
                        .header("Idempotency-Key", "2".repeat(32))
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isCreated());

        String lockId = fxRateService.createRateLock(CurrencyCode.USD, customer.getEmail()).lockId();

        repay(issued.cardReference(), "100.00", lockId, "3".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitedAmount").value(4000.00))
                .andExpect(jsonPath("$.debitedCurrency").value("TRY"))
                .andExpect(jsonPath("$.creditedAmount").value(100.00))
                .andExpect(jsonPath("$.creditedCurrency").value("USD"))
                .andExpect(jsonPath("$.sourceAccountBalanceAfter").value(0.00))
                .andExpect(jsonPath("$.creditCardBalanceAfter").value(0.00));
    }

    @Test
    void repaymentFromAccountInAnotherCurrencyWithoutALockIsRejected() throws Exception {
        CreditCardIssueResponse issued = issueCreditCard(CurrencyCode.USD, "1".repeat(32));

        repay(issued.cardReference(), "10.00", null, "2".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Fx rate lock unavailable"))
                .andExpect(jsonPath("$.detail").value("Lock ID is required for cross-currency repayment"));
    }

    @Test
    void payoutMovesTheWholeCreditBalanceToTheCurrentAccount() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));
        giveCreditBalance(issued.cardReference(), "250.00");

        CreditCardPayoutResponse payout = objectMapper.readValue(
                payOut(issued.cardReference(), null, "2".repeat(32))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.debitedAmount").value(250.00))
                        .andExpect(jsonPath("$.debitedCurrency").value("TRY"))
                        .andExpect(jsonPath("$.creditedAmount").value(250.00))
                        .andExpect(jsonPath("$.creditedCurrency").value("TRY"))
                        .andExpect(jsonPath("$.destinationAccountBalanceAfter").value(250.00))
                        .andExpect(jsonPath("$.creditCardBalanceAfter").value(0.00))
                        .andReturn().getResponse().getContentAsString(),
                CreditCardPayoutResponse.class
        );

        assertThat(bankTransactionRepository.findByReference(payout.transactionReference()).orElseThrow().getTransactionType())
                .isEqualTo(TransactionType.CREDIT_CARD_PAYOUT);
        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("250.00");

        mockMvc.perform(get("/api/credit-cards/{cardReference}", issued.cardReference())
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0.00));
    }

    @Test
    void payingOutTwiceWithSameIdempotencyKeyPaysOutOnce() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));
        giveCreditBalance(issued.cardReference(), "250.00");

        CreditCardPayoutResponse first = objectMapper.readValue(
                payOut(issued.cardReference(), null, "2".repeat(32))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                CreditCardPayoutResponse.class
        );

        payOut(issued.cardReference(), null, "2".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionReference").value(first.transactionReference()))
                .andExpect(jsonPath("$.destinationAccountBalanceAfter").value(250.00));

        payOut(issued.cardReference(), null, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Nothing to pay out"))
                .andExpect(jsonPath("$.detail").value("There is no credit balance to pay out"));

        assertThat(balanceOf(account.getAccountNumber()))
                .isEqualByComparingTo("250.00");
    }

    @Test
    void payoutWithDebtIsRejected() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));

        purchase(issued.cardReference(), account.getAccountNumber(), "100.00", CurrencyCode.TRY, "2".repeat(32))
                .andExpect(status().isCreated());

        payOut(issued.cardReference(), null, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("There is no credit balance to pay out"));
    }

    @Test
    void payoutToAccountInAnotherCurrencyUsesTheLockedRate() throws Exception {
        given(providerRateCache.getLatestRates()).willReturn(new FxProviderRates(
                "test-provider",
                CurrencyCode.USD,
                Map.of(
                        CurrencyCode.USD, BigDecimal.ONE,
                        CurrencyCode.EUR, new BigDecimal("0.85"),
                        CurrencyCode.GBP, new BigDecimal("0.75"),
                        CurrencyCode.TRY, new BigDecimal("40.00")
                ),
                Instant.now()
        ));

        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.USD, "1".repeat(32));
        giveCreditBalance(issued.cardReference(), "100.00");

        String lockId = fxRateService.createRateLock(CurrencyCode.USD, customer.getEmail()).lockId();

        payOut(issued.cardReference(), lockId, "2".repeat(32))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitedAmount").value(100.00))
                .andExpect(jsonPath("$.debitedCurrency").value("USD"))
                .andExpect(jsonPath("$.creditedAmount").value(4000.00))
                .andExpect(jsonPath("$.creditedCurrency").value("TRY"))
                .andExpect(jsonPath("$.destinationAccountBalanceAfter").value(4000.00))
                .andExpect(jsonPath("$.creditCardBalanceAfter").value(0.00));
    }

    @Test
    void closedCreditAccountRejectsPayoutsAndRepayments() throws Exception {
        CreditCardIssueResponse issued = issueAndActivateCreditCard(CurrencyCode.TRY, "1".repeat(32));

        transactionTemplate.executeWithoutResult(status ->
                creditCardRepository.findByCardReference(issued.cardReference()).orElseThrow().getCreditAccount()
                        .close(accountRepository.findByAccountNumber(account.getAccountNumber()).orElseThrow())
        );

        payOut(issued.cardReference(), null, "2".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Credit account closed"))
                .andExpect(jsonPath("$.detail").value("Credit account is closed"));

        repay(issued.cardReference(), "10.00", null, "3".repeat(32))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Credit account closed"));
    }

    private ResultActions payOut(String cardReference, String lockId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/credit-cards/{cardReference}/payouts", cardReference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new CreditCardPayoutRequest(account.getAccountNumber(), lockId)
                ))
                .header("Idempotency-Key", idempotencyKey)
                .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))));
    }

    private CreditAccount generateFirstStatement(String cardReference) {
        CreditAccount creditAccount = creditCardRepository.findByCardReference(cardReference).orElseThrow().getCreditAccount();
        creditStatementService.generateStatementsUpTo(creditAccount.getId(), creditAccount.getNextStatementDate());

        return creditAccount;
    }

}
