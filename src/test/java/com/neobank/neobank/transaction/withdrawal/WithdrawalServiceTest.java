package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.idempotency.IdempotencyRecord;
import com.neobank.neobank.idempotency.IdempotencyService;
import com.neobank.neobank.idempotency.InvalidIdempotencyKeyException;
import com.neobank.neobank.idempotency.RequestHasher;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalResponse;
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
class WithdrawalServiceTest {

    private static final String LEDGER_REFERENCE = "8f3c8a21d9e64b5fa2c17e9084bd6a32";
    private static final String TRANSACTION_REFERENCE = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
    private static final String ENTRY_REFERENCE = "9f3c8a21d9e64b5fa2c17e9084bd6a33";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private IdempotencyService idempotencyService;

    private final RequestHasher requestHasher = new RequestHasher();

    private WithdrawalService withdrawalService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Captor
    private ArgumentCaptor<IdempotencyRecord> idempotencyRecordCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        withdrawalService = new WithdrawalService(
                bankTransactionRepository,
                accountRepository,
                referenceGenerator,
                ledgerPostingService,
                idempotencyService,
                requestHasher
        );
    }

    @Test
    void withdrawDebitsOwnedAccountAndSavesCompletedTransactionWithEntry() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                LEDGER_REFERENCE,
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("1000.00"));

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        String requestHash = requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.WITHDRAWAL.name(),
                        accountNumber,
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.note().trim()
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, ENTRY_REFERENCE);
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        var response = withdrawalService.withdraw(request, accountNumber, email, idempotencyKey);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();
        assertThat(savedTransaction.getEntries()).hasSize(1);
        LedgerEntry savedEntry = savedTransaction.getEntries().getFirst();

        verify(idempotencyService).save(idempotencyRecordCaptor.capture());

        IdempotencyRecord idempotencyRecord = idempotencyRecordCaptor.getValue();

        assertThat(savedTransaction.getReference())
                .isEqualTo(TRANSACTION_REFERENCE);
        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.WITHDRAWAL);
        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);
        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedEntry.getReference())
                .isEqualTo(ENTRY_REFERENCE);
        assertThat(savedEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());
        assertThat(savedEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(savedEntry.getLedgerAccount())
                .isSameAs(ledgerAccount);
        assertThat(savedEntry.getBankTransaction())
                .isSameAs(savedTransaction);
        assertThat(savedEntry.getCurrency())
                .isSameAs(account.getCurrency());
        assertThat(savedEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedEntry.getBalanceAfter());
        assertThat(response.amount())
                .isEqualByComparingTo(savedEntry.getAmount());
        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());
        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());
        assertThat(response.entryReference())
                .isEqualTo(savedEntry.getReference());
        assertThat(response.transactionType())
                .isSameAs(savedTransaction.getTransactionType());
        assertThat(response.currency())
                .isSameAs(savedEntry.getCurrency());
        assertThat(response.note())
                .isEqualTo(savedTransaction.getNote());
        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());
        assertThat(account.getBalance())
                .isEqualByComparingTo("0.00");

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
    void withdrawThrowsInsufficientFundsExceptionWhenBalanceIsInsufficient() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                LEDGER_REFERENCE,
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

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        String requestHash = requestHasher.hashRequest(String.join(
                        "|",
                        TransactionType.WITHDRAWAL.name(),
                        accountNumber,
                        request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        request.note().trim()
                )
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, ENTRY_REFERENCE);
        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(referenceGenerator, times(2)).generate();
        verifyNoInteractions(bankTransactionRepository);
        verify(accountRepository, never()).save(any(Account.class));
        verify(idempotencyService, never()).save(any(IdempotencyRecord.class));
    }

    @Test
    void withdrawThrowsAccountNotFoundExceptionWhenOwnedAccountDoesNotExist() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verifyNoInteractions(bankTransactionRepository, referenceGenerator, idempotencyService);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void withdrawThrowsInvalidIdempotencyKeyExceptionWhenIdempotencyKeyInvalid() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111!11111111111111111";

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email, idempotencyKey))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Invalid idempotency key");

        verifyNoInteractions(bankTransactionRepository, referenceGenerator, idempotencyService, accountRepository);
    }

    @Test
    void withdrawalReturnsOldResponseForExistingIdempotencyKey() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String idempotencyKey = "11111111111111111111111111111111";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                LEDGER_REFERENCE,
                LedgerAccountType.LIABILITY,
                CurrencyCode.TRY
        );
        ledgerAccount.credit(new BigDecimal("1000.00"));

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        String requestHash = requestHasher
                .hashRequest(String
                        .join(
                                "|",
                                TransactionType.WITHDRAWAL.name(),
                                accountNumber,
                                request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                                request.note().trim()
                        )
                );

        BankTransaction transaction = BankTransaction.createNew(
                request.amount(),
                account.getCurrency(),
                TransactionType.WITHDRAWAL,
                TRANSACTION_REFERENCE,
                request.note()
        );

        BigDecimal balanceAfter = ledgerAccount.debit(request.amount());

        transaction.addEntry(
                ENTRY_REFERENCE,
                request.amount(),
                balanceAfter,
                EntryDirection.DEBIT,
                ledgerAccount
        );

        transaction.complete();

        ReflectionTestUtils.setField(ledgerAccount, "id", 1L);

        IdempotencyRecord idempotencyRecord = IdempotencyRecord.createNew(
                idempotencyKey,
                requestHash,
                customer,
                transaction
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        given(idempotencyService.findAndValidateRecord(idempotencyKey, email, requestHash))
                .willReturn(Optional.of(idempotencyRecord));

        WithdrawalResponse response = withdrawalService.withdraw(request, accountNumber, email, idempotencyKey);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(account.getBalance());
        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());
        assertThat(response.transactionReference())
                .isEqualTo(TRANSACTION_REFERENCE);
        assertThat(response.entryReference())
                .isEqualTo(ENTRY_REFERENCE);
        assertThat(response.transactionType())
                .isSameAs(TransactionType.WITHDRAWAL);
        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());
        assertThat(response.currency())
                .isSameAs(account.getCurrency());
        assertThat(response.note())
                .isEqualTo(request.note());

        verify(bankTransactionRepository, never()).save(any());
        verify(idempotencyService, never()).save(any());
        verifyNoInteractions(referenceGenerator);

    }
}
