package com.neobank.neobank.transaction.history;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.card.CardPurchaseDetails;
import com.neobank.neobank.card.CardPurchaseDetailsRepository;
import com.neobank.neobank.card.CardRefundDetails;
import com.neobank.neobank.card.CardRefundDetailsRepository;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.history.dto.CardPurchaseHistoryResponse;
import com.neobank.neobank.transaction.history.dto.CardRefundHistoryResponse;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryFilter;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryItem;
import com.neobank.neobank.transaction.history.dto.TransactionHistoryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private CardPurchaseDetailsRepository cardPurchaseDetailsRepository;

    @Mock
    private CardRefundDetailsRepository cardRefundDetailsRepository;

    @InjectMocks
    private TransactionHistoryService transactionHistoryService;

    @Test
    void historyResolvesRequestedAccountUsingCustomerEmail() {

        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}password-hash",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        Account account = Account.createNew(
                "12345678900987",
                "test",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("appliedAmount")));

        TransactionHistoryFilter filter = new TransactionHistoryFilter(
                account.getAccountNumber(),
                null,
                CurrencyCode.USD,
                null,
                null,
                null,
                null
        );

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "22222222222222222222222222222222",
                null
        );

        LedgerEntry ledgerEntry = transaction.addEntry(
                "33333333333333333333333333333333",
                new BigDecimal("4000.00"),
                new BigDecimal("5000.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        Page<LedgerEntry> page = new PageImpl<>(
                List.of(ledgerEntry),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("amount"))),
                1
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(filter.accountNumber(), customer.getEmail()))
                .willReturn(Optional.of(account));

        given(ledgerEntryRepository.findAll(ArgumentMatchers.<Specification<LedgerEntry>>any(), any(Pageable.class)))
                .willReturn(page);

        Page<TransactionHistoryItem> response =
                transactionHistoryService.getHistoryByAccount(filter, customer.getEmail(), pageRequest);

        assertThat(response.getPageable())
                .isEqualTo(page.getPageable());

        assertThat(response.getContent())
                .isEqualTo(List.of(TransactionHistoryMapper.toResponse(ledgerEntry, account.getAccountNumber(), Map.of(), Map.of())));
    }

    @Test
    void historyRejectsAccountWhenOwnedLookupReturnsEmpty() {
        String email = "customer@example.com";

        TransactionHistoryFilter filter = new TransactionHistoryFilter(
                "12345678900987",
                null,
                null,
                null,
                null,
                null,
                null
        );

        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("appliedAmount")));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(filter.accountNumber(), email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transactionHistoryService.getHistoryByAccount(filter, email, pageRequest))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void customerHistoryAssociatesEntriesWithTheirAccountNumbers() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}password-hash",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount1 = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        LedgerAccount ledgerAccount2 = LedgerAccount.createNew(
                "44444444444444444444444444444444",
                LedgerAccountType.LIABILITY,
                CurrencyCode.USD
        );

        ReflectionTestUtils.setField(ledgerAccount1, "id", 1L);
        ReflectionTestUtils.setField(ledgerAccount2, "id", 2L);

        Account account1 = Account.createNew(
                "12345678900987",
                "test",
                AccountType.CURRENT,
                customer,
                ledgerAccount1
        );

        Account account2 = Account.createNew(
                "98765432100123",
                "another test",
                AccountType.SAVINGS,
                customer,
                ledgerAccount2
        );

        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("appliedAmount")));

        TransactionHistoryFilter filter = new TransactionHistoryFilter(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        BankTransaction transaction1 = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "22222222222222222222222222222222",
                null
        );

        LedgerEntry ledgerEntry1 = transaction1.addEntry(
                "33333333333333333333333333333333",
                new BigDecimal("4000.00"),
                new BigDecimal("5000.00"),
                EntryDirection.CREDIT,
                ledgerAccount1
        );

        BankTransaction transaction2 = BankTransaction.createNew(
                new BigDecimal("110.00"),
                CurrencyCode.USD,
                TransactionType.WITHDRAWAL,
                "55555555555555555555555555555555",
                null
        );

        LedgerEntry ledgerEntry2 = transaction2.addEntry(
                "66666666666666666666666666666666",
                new BigDecimal("4400.00"),
                new BigDecimal("600.00"),
                EntryDirection.CREDIT,
                ledgerAccount2
        );

        Page<LedgerEntry> page = new PageImpl<>(
                List.of(ledgerEntry2, ledgerEntry1),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("amount"))),
                2
        );

        given(accountRepository.findAllByCustomer_Email(customer.getEmail()))
                .willReturn(List.of(account1, account2));

        given(ledgerEntryRepository.findAll(ArgumentMatchers.<Specification<LedgerEntry>>any(), any(Pageable.class)))
                .willReturn(page);

        Page<TransactionHistoryItem> response =
                transactionHistoryService.getHistoryByAccount(filter, customer.getEmail(), pageRequest);

        assertThat(response.getPageable())
                .isEqualTo(page.getPageable());

        assertThat(response.getContent())
                .containsExactly(
                        TransactionHistoryMapper.toResponse(ledgerEntry2, account2.getAccountNumber(), Map.of(), Map.of()),
                        TransactionHistoryMapper.toResponse(ledgerEntry1, account1.getAccountNumber(), Map.of(), Map.of())
                );
    }

    @Test
    void historyPassesNormalizedPaginationToRepositoryAndPreservesPageMetadata() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}password-hash",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "11111111111111111111111111111111",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        Account account = Account.createNew(
                "12345678900987",
                "test",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        TransactionHistoryFilter filter = new TransactionHistoryFilter(
                account.getAccountNumber(),
                null,
                null,
                null,
                null,
                null,
                null
        );

        BankTransaction transaction = BankTransaction.createNew(
                new BigDecimal("100.00"),
                CurrencyCode.USD,
                TransactionType.DEPOSIT,
                "22222222222222222222222222222222",
                null
        );

        LedgerEntry ledgerEntry = transaction.addEntry(
                "33333333333333333333333333333333",
                new BigDecimal("4000.00"),
                new BigDecimal("5000.00"),
                EntryDirection.CREDIT,
                ledgerAccount
        );

        PageRequest pageRequest = PageRequest.of(2, 1, Sort.by(Sort.Order.asc("appliedAmount")));

        Pageable normalizedPageRequest = TransactionHistorySortHelper.normalize(pageRequest);

        Page<LedgerEntry> page = new PageImpl<>(
                List.of(ledgerEntry),
                normalizedPageRequest,
                7
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                filter.accountNumber(), customer.getEmail()
        )).willReturn(Optional.of(account));

        given(ledgerEntryRepository.findAll(ArgumentMatchers.<Specification<LedgerEntry>>any(), eq(normalizedPageRequest)))
                .willReturn(page);

        Page<TransactionHistoryItem> response =
                transactionHistoryService.getHistoryByAccount(filter, customer.getEmail(), pageRequest);

        assertThat(response.getPageable())
                .isEqualTo(normalizedPageRequest);

        assertThat(response.getTotalElements())
                .isEqualTo(7L);

        assertThat(response.getTotalPages())
                .isEqualTo(7);

        assertThat(response.getNumberOfElements())
                .isEqualTo(1);
    }

    @Test
    void customerHistoryReturnsEmptyPageWhenCustomerHasNoAccounts() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}password-hash",
                "Ada Lovelace"
        );

        TransactionHistoryFilter filter = new TransactionHistoryFilter(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("appliedAmount")));

        given(accountRepository.findAllByCustomer_Email(customer.getEmail()))
                .willReturn(List.of());

        given(ledgerEntryRepository.findAll(ArgumentMatchers.<Specification<LedgerEntry>>any(), any(Pageable.class)))
                .willReturn(Page.empty());

        Page<TransactionHistoryItem> response =
                transactionHistoryService.getHistoryByAccount(filter, customer.getEmail(), pageRequest);

        assertThat(response.getContent())
                .isEmpty();
    }

    @Test
    void historyLoadsCardPurchaseDetailsInOneQueryForPayerPurchaseEntries() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}password-hash",
                "Ada Lovelace"
        );

        LedgerAccount fundingLedgerAccount = LedgerAccount.createNew(
                "1".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        LedgerAccount merchantLedgerAccount = LedgerAccount.createNew(
                "2".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        ReflectionTestUtils.setField(fundingLedgerAccount, "id", 1L);
        ReflectionTestUtils.setField(merchantLedgerAccount, "id", 2L);

        Account account = Account.createNew(
                "12".repeat(7),
                "test",
                AccountType.CURRENT,
                customer,
                fundingLedgerAccount
        );

        TransactionHistoryFilter filter = new TransactionHistoryFilter(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt")));

        BankTransaction purchaseTransaction = BankTransaction.createNew(
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                TransactionType.CARD_PURCHASE,
                "3".repeat(32),
                null
        );

        ReflectionTestUtils.setField(purchaseTransaction, "id", 10L);

        LedgerEntry payerEntry = purchaseTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("250.00"),
                new BigDecimal("750.00"),
                EntryDirection.DEBIT,
                fundingLedgerAccount
        );

        purchaseTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("250.00"),
                new BigDecimal("250.00"),
                EntryDirection.CREDIT,
                merchantLedgerAccount
        );

        purchaseTransaction.complete();

        BankTransaction depositTransaction = BankTransaction.createNew(
                new BigDecimal("1000.00"),
                CurrencyCode.TRY,
                TransactionType.DEPOSIT,
                "6".repeat(32),
                null
        );

        ReflectionTestUtils.setField(depositTransaction, "id", 20L);

        LedgerEntry depositEntry = depositTransaction.addEntry(
                "7".repeat(32),
                new BigDecimal("1000.00"),
                new BigDecimal("1000.00"),
                EntryDirection.CREDIT,
                fundingLedgerAccount
        );

        CardPurchaseDetails cardPurchaseDetails = CardPurchaseDetails.createNew(
                purchaseTransaction,
                "Test merchant",
                "8".repeat(32),
                "1234"
        );

        Page<LedgerEntry> page = new PageImpl<>(
                List.of(payerEntry, depositEntry),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"))),
                2
        );

        given(accountRepository.findAllByCustomer_Email(customer.getEmail()))
                .willReturn(List.of(account));

        given(ledgerEntryRepository.findAll(ArgumentMatchers.<Specification<LedgerEntry>>any(), any(Pageable.class)))
                .willReturn(page);

        given(cardPurchaseDetailsRepository.findAllByBankTransaction_IdIn(List.of(10L)))
                .willReturn(List.of(cardPurchaseDetails));

        Page<TransactionHistoryItem> response =
                transactionHistoryService.getHistoryByAccount(filter, customer.getEmail(), pageRequest);

        assertThat(response.getContent())
                .containsExactly(
                        TransactionHistoryMapper.toResponse(payerEntry, account.getAccountNumber(), Map.of(10L, cardPurchaseDetails), Map.of()),
                        TransactionHistoryMapper.toResponse(depositEntry, account.getAccountNumber(), Map.of(), Map.of())
                );

        assertThat(response.getContent().get(0))
                .isInstanceOf(CardPurchaseHistoryResponse.class);

        assertThat(response.getContent().get(1))
                .isInstanceOf(TransactionHistoryResponse.class);

        verify(cardPurchaseDetailsRepository).findAllByBankTransaction_IdIn(List.of(10L));
    }

    @Test
    void historySkipsCardPurchaseDetailsLookupWhenPageHasNoPayerPurchaseEntries() {
        Customer customer = Customer.createNew(
                "merchant@example.com",
                "{bcrypt}password-hash",
                "Ada Lovelace"
        );

        LedgerAccount payerLedgerAccount = LedgerAccount.createNew(
                "1".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        LedgerAccount merchantLedgerAccount = LedgerAccount.createNew(
                "2".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        ReflectionTestUtils.setField(payerLedgerAccount, "id", 1L);
        ReflectionTestUtils.setField(merchantLedgerAccount, "id", 2L);

        Account merchantAccount = Account.createNew(
                "98".repeat(7),
                "merchant",
                AccountType.CURRENT,
                customer,
                merchantLedgerAccount
        );

        TransactionHistoryFilter filter = new TransactionHistoryFilter(
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt")));

        BankTransaction purchaseTransaction = BankTransaction.createNew(
                new BigDecimal("250.00"),
                CurrencyCode.TRY,
                TransactionType.CARD_PURCHASE,
                "3".repeat(32),
                null
        );

        ReflectionTestUtils.setField(purchaseTransaction, "id", 10L);

        purchaseTransaction.addEntry(
                "4".repeat(32),
                new BigDecimal("250.00"),
                new BigDecimal("750.00"),
                EntryDirection.DEBIT,
                payerLedgerAccount
        );

        LedgerEntry merchantEntry = purchaseTransaction.addEntry(
                "5".repeat(32),
                new BigDecimal("250.00"),
                new BigDecimal("250.00"),
                EntryDirection.CREDIT,
                merchantLedgerAccount
        );

        Page<LedgerEntry> page = new PageImpl<>(
                List.of(merchantEntry),
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"))),
                1
        );

        given(accountRepository.findAllByCustomer_Email(customer.getEmail()))
                .willReturn(List.of(merchantAccount));

        given(ledgerEntryRepository.findAll(ArgumentMatchers.<Specification<LedgerEntry>>any(), any(Pageable.class)))
                .willReturn(page);

        Page<TransactionHistoryItem> response =
                transactionHistoryService.getHistoryByAccount(filter, customer.getEmail(), pageRequest);

        assertThat(response.getContent())
                .containsExactly(
                        TransactionHistoryMapper.toResponse(merchantEntry, merchantAccount.getAccountNumber(), Map.of(), Map.of())
                );

        assertThat(response.getContent().get(0))
                .isInstanceOf(TransactionHistoryResponse.class);

        verifyNoInteractions(cardPurchaseDetailsRepository, cardRefundDetailsRepository);
    }

    @Test
    void historyLoadsCardRefundDetailsInOneQueryForCardholderRefundEntries() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}password-hash",
                "Ada Lovelace"
        );

        LedgerAccount fundingLedgerAccount = LedgerAccount.createNew("1".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY);
        LedgerAccount merchantLedgerAccount = LedgerAccount.createNew("2".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY);

        ReflectionTestUtils.setField(fundingLedgerAccount, "id", 1L);
        ReflectionTestUtils.setField(merchantLedgerAccount, "id", 2L);

        Account account = Account.createNew("12".repeat(7), "test", AccountType.CURRENT, customer, fundingLedgerAccount);

        TransactionHistoryFilter filter = new TransactionHistoryFilter(null, null, null, null, null, null, null);
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt")));

        BankTransaction purchaseTransaction = BankTransaction.createNew(
                new BigDecimal("250.00"), CurrencyCode.TRY, TransactionType.CARD_PURCHASE, "3".repeat(32), null
        );
        ReflectionTestUtils.setField(purchaseTransaction, "id", 10L);

        LedgerEntry purchaseDebit = purchaseTransaction.addEntry(
                "4".repeat(32), new BigDecimal("250.00"), new BigDecimal("750.00"), EntryDirection.DEBIT, fundingLedgerAccount
        );
        purchaseTransaction.addEntry(
                "5".repeat(32), new BigDecimal("250.00"), new BigDecimal("250.00"), EntryDirection.CREDIT, merchantLedgerAccount
        );
        purchaseTransaction.complete();

        CardPurchaseDetails cardPurchaseDetails = CardPurchaseDetails.createNew(
                purchaseTransaction, "Test merchant", "9".repeat(32), "1234"
        );
        purchaseTransaction.markRefunded();

        BankTransaction refundTransaction = BankTransaction.createNew(
                new BigDecimal("250.00"), CurrencyCode.TRY, TransactionType.CARD_REFUND, "6".repeat(32), null
        );
        ReflectionTestUtils.setField(refundTransaction, "id", 20L);

        refundTransaction.addEntry(
                "7".repeat(32), new BigDecimal("250.00"), new BigDecimal("0.00"), EntryDirection.DEBIT, merchantLedgerAccount
        );
        LedgerEntry refundCredit = refundTransaction.addEntry(
                "8".repeat(32), new BigDecimal("250.00"), new BigDecimal("1000.00"), EntryDirection.CREDIT, fundingLedgerAccount
        );
        refundTransaction.complete();

        CardRefundDetails cardRefundDetails = CardRefundDetails.createNew(refundTransaction, cardPurchaseDetails);

        Page<LedgerEntry> page = new PageImpl<>(List.of(refundCredit, purchaseDebit), pageRequest, 2);

        given(accountRepository.findAllByCustomer_Email(customer.getEmail()))
                .willReturn(List.of(account));

        given(ledgerEntryRepository.findAll(ArgumentMatchers.<Specification<LedgerEntry>>any(), any(Pageable.class)))
                .willReturn(page);

        given(cardPurchaseDetailsRepository.findAllByBankTransaction_IdIn(List.of(10L)))
                .willReturn(List.of(cardPurchaseDetails));

        given(cardRefundDetailsRepository.findAllByBankTransaction_IdIn(List.of(20L)))
                .willReturn(List.of(cardRefundDetails));

        Page<TransactionHistoryItem> response =
                transactionHistoryService.getHistoryByAccount(filter, customer.getEmail(), pageRequest);

        assertThat(response.getContent())
                .hasSize(2);

        assertThat(response.getContent().get(0))
                .isInstanceOf(CardRefundHistoryResponse.class);

        assertThat(response.getContent().get(1))
                .isInstanceOf(CardPurchaseHistoryResponse.class);

        CardRefundHistoryResponse refundRow = (CardRefundHistoryResponse) response.getContent().get(0);
        CardPurchaseHistoryResponse purchaseRow = (CardPurchaseHistoryResponse) response.getContent().get(1);

        assertThat(refundRow.purchaseReference())
                .isEqualTo(purchaseRow.transactionReference());

        verify(cardRefundDetailsRepository).findAllByBankTransaction_IdIn(List.of(20L));
    }
}
