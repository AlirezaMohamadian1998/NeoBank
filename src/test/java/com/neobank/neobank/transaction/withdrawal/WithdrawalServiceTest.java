package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.ledger.InsufficientFundsException;
import com.neobank.neobank.ledger.LedgerAccount;
import com.neobank.neobank.ledger.LedgerAccountType;
import com.neobank.neobank.ledger.LedgerPostingService;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import com.neobank.neobank.transaction.BankTransaction;
import com.neobank.neobank.transaction.BankTransactionRepository;
import com.neobank.neobank.transaction.EntryDirection;
import com.neobank.neobank.transaction.LedgerEntry;
import com.neobank.neobank.transaction.TransactionStatus;
import com.neobank.neobank.transaction.TransactionType;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    private WithdrawalService withdrawalService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        withdrawalService = new WithdrawalService(
                bankTransactionRepository,
                accountRepository,
                referenceGenerator,
                ledgerPostingService
        );
    }

    @Test
    void withdrawDebitsOwnedAccountAndSavesCompletedTransactionWithEntry() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

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

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, ENTRY_REFERENCE);
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = withdrawalService.withdraw(request, accountNumber, email);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();
        assertThat(savedTransaction.getEntries()).hasSize(1);
        LedgerEntry savedEntry = savedTransaction.getEntries().getFirst();

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

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(referenceGenerator, times(2)).generate();
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void withdrawThrowsInsufficientFundsExceptionWhenBalanceIsInsufficient() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

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

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, ENTRY_REFERENCE);

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(referenceGenerator, times(2)).generate();
        verifyNoInteractions(bankTransactionRepository);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void withdrawThrowsAccountNotFoundExceptionWhenOwnedAccountDoesNotExist() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verifyNoInteractions(bankTransactionRepository, referenceGenerator);
        verify(accountRepository, never()).save(any(Account.class));
    }
}
