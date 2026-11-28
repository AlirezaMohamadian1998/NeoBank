package com.neobank.neobank.transaction.history;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionApiIntegrationTestSupport;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TransactionHistoryApiIntegrationTest extends TransactionApiIntegrationTestSupport {


    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void authenticatedCustomerCanReadFilteredOwnedAccountHistory() throws Exception {
        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("120.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                "1".repeat(32),
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "2".repeat(32),
                new BigDecimal("120.00"),
                new BigDecimal("120.00"),
                EntryDirection.CREDIT,
                account.getLedgerAccount()
        );

        depositTransaction.addEntry(
                "3".repeat(32),
                new BigDecimal("120.00"),
                new BigDecimal("10120.00"),
                EntryDirection.DEBIT,
                internalAccountTRY.getLedgerAccount()
        );

        BankTransaction depositTransaction2 = BankTransaction.createNew(
                new BigDecimal("140.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                "7".repeat(32),
                null
        );

        depositTransaction2.addEntry(
                "8".repeat(32),
                new BigDecimal("140.00"),
                new BigDecimal("260.00"),
                EntryDirection.CREDIT,
                account.getLedgerAccount()
        );

        depositTransaction2.addEntry(
                "9".repeat(32),
                new BigDecimal("140.00"),
                new BigDecimal("10260.00"),
                EntryDirection.DEBIT,
                internalAccountTRY.getLedgerAccount()
        );

        BankTransaction withdrawalTransaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.TRY,
                TransactionType.WITHDRAWAL,
                "4".repeat(32),
                null
        );

        LedgerEntry customerWithdrawalEntry = withdrawalTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("100.00"),
                new BigDecimal("160.00"),
                EntryDirection.DEBIT,
                account.getLedgerAccount()
        );

        withdrawalTransaction.addEntry(
                "6".repeat(32),
                new BigDecimal("100.00"),
                new BigDecimal("10160.00"),
                EntryDirection.CREDIT,
                internalAccountTRY.getLedgerAccount()
        );

        depositTransaction.complete();
        depositTransaction2.complete();
        withdrawalTransaction.complete();

        List<BankTransaction> savedTransactions =
                bankTransactionRepository.saveAll(List.of(depositTransaction, depositTransaction2, withdrawalTransaction));

        MvcResult result = mockMvc.perform(get("/api/transactions")
                        .param("accountNumber", account.getAccountNumber())
                        .param("appliedMin", "100.00")
                        .param("appliedMax", "120.00")
                        .param("page", "0")
                        .param("size", "25")
                        .param("sort", "appliedAmount,asc")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccountReference").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerEntryId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].accountId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccountId").doesNotHaveJsonPath())
                .andReturn();

        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString()
        );

        List<TransactionHistoryResponse> responses =
                objectMapper.readerForListOf(TransactionHistoryResponse.class)
                        .readValue(root.get("content"));

        assertThat(responses.size())
                .isEqualTo(2);

        TransactionHistoryResponse firstResponse = responses.getFirst();
        TransactionHistoryResponse lastResponse = responses.getLast();

        assertThat(firstResponse.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(firstResponse.entryReference())
                .isEqualTo(customerWithdrawalEntry.getReference());

        assertThat(firstResponse.transactionType())
                .isSameAs(withdrawalTransaction.getTransactionType());

        assertThat(firstResponse.direction())
                .isSameAs(customerWithdrawalEntry.getDirection());

        assertThat(firstResponse.appliedAmount())
                .isEqualByComparingTo(customerWithdrawalEntry.getAmount());

        assertThat(firstResponse.accountCurrency())
                .isSameAs(account.getCurrency());

        assertThat(firstResponse.requestedAmount())
                .isEqualByComparingTo(withdrawalTransaction.getRequestedAmount());

        assertThat(firstResponse.requestedCurrency())
                .isSameAs(withdrawalTransaction.getRequestedCurrency());

        assertThat(firstResponse.balanceAfter())
                .isEqualByComparingTo(customerWithdrawalEntry.getBalanceAfter());

        assertThat(firstResponse.createdAt())
                .isNotNull();

        assertThat(lastResponse.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(lastResponse.entryReference())
                .isEqualTo(customerDepositEntry.getReference());

        assertThat(lastResponse.transactionType())
                .isSameAs(depositTransaction.getTransactionType());

        assertThat(lastResponse.direction())
                .isSameAs(customerDepositEntry.getDirection());

        assertThat(lastResponse.appliedAmount())
                .isEqualByComparingTo(customerDepositEntry.getAmount());

        assertThat(lastResponse.accountCurrency())
                .isSameAs(account.getCurrency());

        assertThat(lastResponse.requestedAmount())
                .isEqualByComparingTo(depositTransaction.getRequestedAmount());

        assertThat(lastResponse.requestedCurrency())
                .isSameAs(depositTransaction.getRequestedCurrency());

        assertThat(lastResponse.balanceAfter())
                .isEqualByComparingTo(customerDepositEntry.getBalanceAfter());

        assertThat(lastResponse.createdAt())
                .isNotNull();

        assertThat(bankTransactionRepository.count())
                .isEqualTo(3);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(6);
    }

    @Test
    void customerHistoryPaginatesAcrossOwnedAccountsWithoutExposingOtherCustomersEntries() throws Exception {
        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("120.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                "1".repeat(32),
                null
        );

        LedgerEntry customerDepositEntry = depositTransaction.addEntry(
                "2".repeat(32),
                new BigDecimal("120.00"),
                new BigDecimal("120.00"),
                EntryDirection.CREDIT,
                account.getLedgerAccount()
        );

        depositTransaction.addEntry(
                "3".repeat(32),
                new BigDecimal("120.00"),
                new BigDecimal("10120.00"),
                EntryDirection.DEBIT,
                internalAccountTRY.getLedgerAccount()
        );

        Account account2 = accountRepository.save(
                Account.createNew(
                        "14725836995175",
                        "7".repeat(32),
                        "Test 2",
                        AccountType.CURRENT,
                        customer,
                        CurrencyCode.TRY
                )
        );

        BankTransaction depositTransactionAcc2 = BankTransaction.createNew(
                new BigDecimal("1200.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                "4".repeat(32),
                null
        );

        LedgerEntry customerDepositEntryAcc2 = depositTransactionAcc2.addEntry(
                "5".repeat(32),
                new BigDecimal("1200.00"),
                new BigDecimal("1200.00"),
                EntryDirection.CREDIT,
                account2.getLedgerAccount()
        );

        depositTransactionAcc2.addEntry(
                "6".repeat(32),
                new BigDecimal("1200.00"),
                new BigDecimal("11320.00"),
                EntryDirection.DEBIT,
                internalAccountTRY.getLedgerAccount()
        );

        Customer customer2 = customerRepository.save(
                Customer.createNew(
                        "example@customer.com",
                        "{bcrypt}hashed-password",
                        "Lovelace Ada"
                )
        );

        Account accountForCustomer2 = accountRepository.save(
                Account.createNew(
                        "96385274132145",
                        "8".repeat(32),
                        "Test 3",
                        AccountType.CURRENT,
                        customer2,
                        CurrencyCode.USD
                )
        );

        BankTransaction depositTransactionForCustomer2 = BankTransaction.createNew(
                new BigDecimal("1200.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "9".repeat(32),
                null
        );

        depositTransactionForCustomer2.addEntry(
                "10".repeat(16),
                new BigDecimal("1200.00"),
                new BigDecimal("1200.00"),
                EntryDirection.CREDIT,
                accountForCustomer2.getLedgerAccount()
        );

        depositTransactionForCustomer2.addEntry(
                "11".repeat(16),
                new BigDecimal("1200.00"),
                new BigDecimal("11200.00"),
                EntryDirection.DEBIT,
                internalAccountUSD.getLedgerAccount()
        );

        depositTransaction.complete();
        depositTransactionAcc2.complete();
        depositTransactionForCustomer2.complete();

        bankTransactionRepository.saveAll(List.of(depositTransaction, depositTransactionAcc2, depositTransactionForCustomer2));

        Timestamp sharedTimestamp =
                Timestamp.from(Instant.parse("2026-09-19T12:00:00Z"));

        int updatedEntries = jdbcTemplate.update(
                "UPDATE ledger_entries SET created_at = ? WHERE id IN (?, ?)",
                sharedTimestamp,
                customerDepositEntry.getId(),
                customerDepositEntryAcc2.getId()
        );

        assertThat(updatedEntries)
                .isEqualTo(2);

        MvcResult result = mockMvc.perform(get("/api/transactions")
                        .param("page", "0")
                        .param("size", "1")
                        .param("sort", "createdAt,desc")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccountReference").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerEntryId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].accountId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccountId").doesNotHaveJsonPath())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

        List<TransactionHistoryResponse> firstPageResponses =
                objectMapper.readerForListOf(TransactionHistoryResponse.class)
                        .readValue(root.get("content"));

        assertThat(firstPageResponses.size())
                .isEqualTo(1);

        assertThat(root.get("page").get("number").asInt())
                .isEqualTo(0);

        assertThat(root.get("page").get("size").asInt())
                .isEqualTo(1);

        assertThat(root.get("page").get("totalElements").asInt())
                .isEqualTo(2);

        assertThat(root.get("page").get("totalPages").asInt())
                .isEqualTo(2);

        TransactionHistoryResponse firstResponse = firstPageResponses.getFirst();

        assertThat(firstResponse.accountNumber())
                .isEqualTo(account2.getAccountNumber());

        assertThat(firstResponse.entryReference())
                .isEqualTo(customerDepositEntryAcc2.getReference());

        assertThat(firstResponse.transactionType())
                .isSameAs(depositTransactionAcc2.getTransactionType());

        assertThat(firstResponse.direction())
                .isSameAs(customerDepositEntryAcc2.getDirection());

        assertThat(firstResponse.appliedAmount())
                .isEqualByComparingTo(customerDepositEntryAcc2.getAmount());

        assertThat(firstResponse.accountCurrency())
                .isSameAs(account2.getCurrency());

        assertThat(firstResponse.requestedAmount())
                .isEqualByComparingTo(depositTransactionAcc2.getRequestedAmount());

        assertThat(firstResponse.requestedCurrency())
                .isSameAs(depositTransactionAcc2.getRequestedCurrency());

        assertThat(firstResponse.balanceAfter())
                .isEqualByComparingTo(customerDepositEntryAcc2.getBalanceAfter());

        assertThat(firstResponse.createdAt())
                .isNotNull();

        MvcResult result2 = mockMvc.perform(get("/api/transactions")
                        .param("page", "1")
                        .param("size", "1")
                        .param("sort", "createdAt,desc")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[*].version").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccount").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].customer").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].account").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccountReference").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerEntryId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].accountId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.content[*].ledgerAccountId").doesNotHaveJsonPath())
                .andReturn();

        JsonNode root2 = objectMapper.readTree(result2.getResponse().getContentAsString());

        List<TransactionHistoryResponse> secondPageResponses =
                objectMapper.readerForListOf(TransactionHistoryResponse.class)
                        .readValue(root2.get("content"));

        assertThat(secondPageResponses.size())
                .isEqualTo(1);

        assertThat(root2.get("page").get("number").asInt())
                .isEqualTo(1);

        assertThat(root2.get("page").get("size").asInt())
                .isEqualTo(1);

        assertThat(root2.get("page").get("totalElements").asInt())
                .isEqualTo(2);

        assertThat(root2.get("page").get("totalPages").asInt())
                .isEqualTo(2);

        TransactionHistoryResponse lastResponse = secondPageResponses.getLast();

        assertThat(lastResponse.accountNumber())
                .isEqualTo(account.getAccountNumber());

        assertThat(lastResponse.entryReference())
                .isEqualTo(customerDepositEntry.getReference());

        assertThat(lastResponse.transactionType())
                .isSameAs(depositTransaction.getTransactionType());

        assertThat(lastResponse.direction())
                .isSameAs(customerDepositEntry.getDirection());

        assertThat(lastResponse.appliedAmount())
                .isEqualByComparingTo(customerDepositEntry.getAmount());

        assertThat(lastResponse.accountCurrency())
                .isSameAs(account.getCurrency());

        assertThat(lastResponse.requestedAmount())
                .isEqualByComparingTo(depositTransaction.getRequestedAmount());

        assertThat(lastResponse.requestedCurrency())
                .isSameAs(depositTransaction.getRequestedCurrency());

        assertThat(lastResponse.balanceAfter())
                .isEqualByComparingTo(customerDepositEntry.getBalanceAfter());

        assertThat(lastResponse.createdAt())
                .isNotNull();

        assertThat(bankTransactionRepository.count())
                .isEqualTo(3);

        assertThat(ledgerEntryRepository.count())
                .isEqualTo(6);
    }

    @Test
    void authenticatedCustomerCannotReadAnotherCustomersAccountHistory() throws Exception {
        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("120.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                "1".repeat(32),
                null
        );

        depositTransaction.addEntry(
                "2".repeat(32),
                new BigDecimal("120.00"),
                new BigDecimal("120.00"),
                EntryDirection.CREDIT,
                account.getLedgerAccount()
        );

        depositTransaction.addEntry(
                "3".repeat(32),
                new BigDecimal("120.00"),
                new BigDecimal("10120.00"),
                EntryDirection.DEBIT,
                internalAccountTRY.getLedgerAccount()
        );

        Customer customer2 = customerRepository.save(
                Customer.createNew(
                        "example@customer.com",
                        "{bcrypt}hashed-password",
                        "Lovelace Ada"
                )
        );

        Account accountForCustomer2 = accountRepository.save(
                Account.createNew(
                        "96385274132145",
                        "8".repeat(32),
                        "Test 3",
                        AccountType.CURRENT,
                        customer2,
                        CurrencyCode.USD
                )
        );

        BankTransaction depositTransactionForCustomer2 = BankTransaction.createNew(
                new BigDecimal("1200.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "9".repeat(32),
                null
        );

        depositTransactionForCustomer2.addEntry(
                "10".repeat(16),
                new BigDecimal("1200.00"),
                new BigDecimal("1200.00"),
                EntryDirection.CREDIT,
                accountForCustomer2.getLedgerAccount()
        );

        depositTransactionForCustomer2.addEntry(
                "11".repeat(16),
                new BigDecimal("1200.00"),
                new BigDecimal("11200.00"),
                EntryDirection.DEBIT,
                internalAccountUSD.getLedgerAccount()
        );

        depositTransaction.complete();
        depositTransactionForCustomer2.complete();

        bankTransactionRepository.saveAll(List.of(depositTransaction, depositTransactionForCustomer2));

        mockMvc.perform(get("/api/transactions")
                        .param("accountNumber", accountForCustomer2.getAccountNumber())
                        .param("page", "0")
                        .param("size", "10")
                        .param("sort", "createdAt,desc")
                        .with(jwt().jwt(jwt -> jwt.subject(customer.getEmail()))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Account not found"))
                .andExpect(jsonPath("$.instance").value("/api/transactions"))
                .andExpect(jsonPath("$.title").value("Account not found"));
    }
}
