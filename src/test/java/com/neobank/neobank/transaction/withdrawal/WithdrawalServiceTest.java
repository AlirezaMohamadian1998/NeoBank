package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.account.*;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.withdrawal.dto.WithdrawalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private AccountEntryRepository accountEntryRepository;

    @Mock
    private TransactionReferenceGenerator transactionReferenceGenerator;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private WithdrawalService withdrawalService;

    @Captor
    ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Captor
    ArgumentCaptor<AccountEntry> accountEntryCaptor;

    @Test
    void withdrawDebitsOwnedAccountAndPersistsTransactionAndEntry() {
        String email = "customer@example.com";
        String accountNumber = "12345678900987";
        String reference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY,
                customer
        );

        account.credit(new BigDecimal("1000.00"));

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(transactionReferenceGenerator.generate())
                .willReturn(reference);
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(accountEntryRepository.save(any(AccountEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = withdrawalService.withdraw(request, accountNumber, email);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());
        verify(accountEntryRepository).save(accountEntryCaptor.capture());

        var savedTransaction = bankTransactionCaptor.getValue();
        var savedEntry = accountEntryCaptor.getValue();

        assertThat(savedTransaction.getReference())
                .isEqualTo(reference);
        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.WITHDRAWAL);
        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());
        assertThat(savedEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(savedEntry.getAccount())
                .isSameAs(account);
        assertThat(savedEntry.getBankTransaction())
                .isSameAs(savedTransaction);
        assertThat(savedEntry.getCurrency())
                .isSameAs(account.getCurrency());
        assertThat(savedEntry.getEntryDirection())
                .isSameAs(EntryDirection.DEBIT);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedEntry.getBalanceAfter());
        assertThat(response.amount())
                .isEqualByComparingTo(savedEntry.getAmount());
        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());
        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());
        assertThat(response.transactionType())
                .isSameAs(savedTransaction.getTransactionType());
        assertThat(response.currency())
                .isSameAs(savedEntry.getCurrency());
        assertThat(response.note())
                .isEqualTo(savedTransaction.getNote());
        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(transactionReferenceGenerator).generate();
        verify(accountRepository,never()).save(any(Account.class));
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

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY,
                customer
        );

        WithdrawalRequest request = new WithdrawalRequest(new BigDecimal("1000.00"), "Test");

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));

        assertThatThrownBy(() -> withdrawalService.withdraw(request, accountNumber, email))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(accountRepository,never()).save(any(Account.class));

        verifyNoInteractions(accountEntryRepository, bankTransactionRepository, transactionReferenceGenerator);
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
        verify(accountRepository,never()).save(any(Account.class));

        verifyNoInteractions(accountEntryRepository, bankTransactionRepository, transactionReferenceGenerator);
    }
}
