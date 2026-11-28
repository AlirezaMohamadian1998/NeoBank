package com.neobank.neobank.transaction.history;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.card.payment.CardPurchaseDetails;
import com.neobank.neobank.card.payment.CardPurchaseDetailsRepository;
import com.neobank.neobank.card.payment.CardRefundDetails;
import com.neobank.neobank.card.payment.CardRefundDetailsRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.neobank.neobank.support.TestFixtures.currentAccount;
import static com.neobank.neobank.support.TestFixtures.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private TransactionHistoryService transactionHistoryService;

    @BeforeEach
    void setUp() {
        transactionHistoryService = new TransactionHistoryService(
                ledgerEntryRepository,
                accountRepository,
                new TransactionHistoryItemAssembler(cardPurchaseDetailsRepository, cardRefundDetailsRepository)
        );
    }

    @Test
    void historyLoadsCardPurchaseDetailsInOneQueryForPayerPurchaseEntries() {
        Customer customer = customer("customer@example.com");

        LedgerAccount merchantLedgerAccount = LedgerAccount.createNew(
                "2".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        ReflectionTestUtils.setField(merchantLedgerAccount, "id", 2L);

        Account account = currentAccount("12".repeat(7), "1".repeat(32), customer, CurrencyCode.TRY);

        ReflectionTestUtils.setField(account.getLedgerAccount(), "id", 1L);

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
                account.getLedgerAccount()
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
                account.getLedgerAccount()
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
        Customer customer = customer("merchant@example.com");

        LedgerAccount payerLedgerAccount = LedgerAccount.createNew(
                "1".repeat(32),
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        ReflectionTestUtils.setField(payerLedgerAccount, "id", 1L);

        Account merchantAccount = currentAccount("98".repeat(7), "2".repeat(32), customer, CurrencyCode.TRY);

        ReflectionTestUtils.setField(merchantAccount.getLedgerAccount(), "id", 2L);

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
                merchantAccount.getLedgerAccount()
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
        Customer customer = customer("customer@example.com");

        LedgerAccount merchantLedgerAccount = LedgerAccount.createNew("2".repeat(32), LedgerAccountType.LIABILITY, CurrencyCode.TRY);

        ReflectionTestUtils.setField(merchantLedgerAccount, "id", 2L);

        Account account = currentAccount("12".repeat(7), "1".repeat(32), customer, CurrencyCode.TRY);

        ReflectionTestUtils.setField(account.getLedgerAccount(), "id", 1L);

        TransactionHistoryFilter filter = new TransactionHistoryFilter(null, null, null, null, null, null, null);
        PageRequest pageRequest = PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt")));

        BankTransaction purchaseTransaction = BankTransaction.createNew(
                new BigDecimal("250.00"), CurrencyCode.TRY, TransactionType.CARD_PURCHASE, "3".repeat(32), null
        );
        ReflectionTestUtils.setField(purchaseTransaction, "id", 10L);

        LedgerEntry purchaseDebit = purchaseTransaction.addEntry(
                "4".repeat(32), new BigDecimal("250.00"), new BigDecimal("750.00"), EntryDirection.DEBIT, account.getLedgerAccount()
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
                "8".repeat(32), new BigDecimal("250.00"), new BigDecimal("1000.00"), EntryDirection.CREDIT, account.getLedgerAccount()
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
