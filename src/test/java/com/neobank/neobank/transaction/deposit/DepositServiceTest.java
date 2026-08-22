package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private IdempotencyService idempotencyService;

    private final RequestHasher requestHasher = new RequestHasher();

    private DepositService depositService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Captor
    private ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        depositService = new DepositService(
                accountRepository,
                bankTransactionRepository,
                referenceGenerator,
                ledgerPostingService,
                requestHasher,
                idempotencyService
        );
    }

    @Test
    void depositCreditsOwnedAccountAndSavesCompletedTransactionWithEntry() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String entryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
        String idempotencyKey = "11111111111111111111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        String requestHash = requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.DEPOSIT.name(),
                        accountNumber,
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.note().trim()
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(referenceGenerator.generate())
                .willReturn(transactionReference, entryReference);
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        DepositResponse response = depositService.deposit(request, accountNumber, email, idempotencyKey);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();
        assertThat(savedTransaction.getEntries()).hasSize(1);
        LedgerEntry savedEntry = savedTransaction.getEntries().getFirst();

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(savedTransaction.getReference())
                .isEqualTo(transactionReference);
        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.DEPOSIT);
        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);
        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedEntry.getReference())
                .isEqualTo(entryReference);
        assertThat(savedEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(savedEntry.getCurrency())
                .isSameAs(account.getCurrency());
        assertThat(savedEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());
        assertThat(savedEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(savedEntry.getBankTransaction())
                .isSameAs(savedTransaction);
        assertThat(savedEntry.getLedgerAccount())
                .isSameAs(ledgerAccount);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedEntry.getBalanceAfter());
        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());
        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());
        assertThat(response.entryReference())
                .isEqualTo(savedEntry.getReference());
        assertThat(response.transactionType())
                .isSameAs(savedTransaction.getTransactionType());
        assertThat(response.amount())
                .isEqualByComparingTo(savedEntry.getAmount());
        assertThat(response.note())
                .isEqualTo(savedTransaction.getNote());
        assertThat(response.currency())
                .isSameAs(savedEntry.getCurrency());
        assertThat(account.getBalance())
                .isEqualByComparingTo(savedEntry.getBalanceAfter());

        assertThat(idempotencyRecord.getIdempotencyKey())
                .isEqualTo(idempotencyKey);
        assertThat(idempotencyRecord.getCustomer())
                .isSameAs(customer);
        assertThat(idempotencyRecord.getBankTransaction())
                .isSameAs(savedTransaction);
        assertThat(idempotencyRecord.getRequestHash())
                .isEqualTo(
                        requestHasher.hashRequest(String.join(
                                        "|",
                                        savedTransaction.getTransactionType().name(),
                                        accountNumber,
                                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                        request.note().trim()
                                )
                        )
                );

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(referenceGenerator, times(2)).generate();
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void depositThrowsAccountNotFoundExceptionWhenOwnedAccountDoesNotExist() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verifyNoInteractions(referenceGenerator, bankTransactionRepository, idempotencyService);
    }

    @Test
    void depositThrowsInvalidIdempotencyKeyExceptionForInvalidIdempotencyKey() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "1111111111111111!111111111111111";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(referenceGenerator, bankTransactionRepository, idempotencyService, accountRepository);
    }

    @Test
    void depositWithExistingIdempotencyKeyReturnsTheOldResponse() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String idempotencyKey = "11111111111111111111111111111111";
        String transactionReference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
        String entryReference = "9f3c8a21d9e64b5fa2c17e9084bd6a33";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                "8f3c8a21d9e64b5fa2c17e9084bd6a32",
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        String requestHash = requestHasher
                .hashRequest(String
                        .join(
                                "|",
                                TransactionType.DEPOSIT.name(),
                                accountNumber,
                                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                request.note().trim()
                        )
                );

        BankTransaction bankTransaction = BankTransaction.createNew(
                request.amount(),
                account.getCurrency(),
                TransactionType.DEPOSIT,
                transactionReference,
                request.note()
        );

        BigDecimal balanceAfter = ledgerAccount.credit(request.amount());

        bankTransaction.addEntry(
                entryReference,
                request.amount(),
                balanceAfter,
                EntryDirection.CREDIT,
                ledgerAccount
        );

        bankTransaction.complete();

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                bankTransaction
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        DepositResponse response = depositService.deposit(request, accountNumber, email, idempotencyKey);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(account.getBalance());
        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());
        assertThat(response.transactionReference())
                .isEqualTo(transactionReference);
        assertThat(response.entryReference())
                .isEqualTo(entryReference);
        assertThat(response.transactionType())
                .isSameAs(TransactionType.DEPOSIT);

        verify(bankTransactionRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(referenceGenerator);
    }
}
