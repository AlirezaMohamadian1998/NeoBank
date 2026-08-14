package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.*;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.deposit.dto.DepositRequest;
import com.neobank.neobank.transaction.deposit.dto.DepositResponse;
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
class DepositServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private AccountEntryRepository accountEntryRepository;

    @Mock
    private TransactionReferenceGenerator transactionReferenceGenerator;

    @InjectMocks
    private DepositService depositService;

    @Captor
    private ArgumentCaptor<AccountEntry> accountEntryCaptor;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Test
    void depositCreditsOwnedAccountAndPersistsTransactionAndEntry() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
        String reference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        Account account = Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY,
                customer
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(transactionReferenceGenerator.generate())
                .willReturn(reference);
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(accountEntryRepository.save(any(AccountEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        DepositResponse response = depositService.deposit(request, accountNumber, email);

        verify(accountEntryRepository).save(accountEntryCaptor.capture());
        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();
        AccountEntry savedEntry = accountEntryCaptor.getValue();

        assertThat(savedTransaction.getReference())
                .isEqualTo(reference);
        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.DEPOSIT);
        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(savedEntry.getAccount())
                .isSameAs(account);
        assertThat(savedEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(savedEntry.getCurrency())
                .isSameAs(account.getCurrency());
        assertThat(savedEntry.getBalanceAfter())
                .isEqualByComparingTo(account.getBalance());
        assertThat(savedEntry.getEntryDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(savedEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(response.balanceAfter())
                .isEqualByComparingTo(savedEntry.getBalanceAfter());
        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());
        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());
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

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(transactionReferenceGenerator).generate();
        verify(accountRepository,never()).save(any(Account.class));
    }

    @Test
    void depositThrowsAccountNotFoundExceptionWhenOwnedAccountDoesNotExist() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";

        DepositRequest request = new DepositRequest(
                new BigDecimal("1000.00"),
                "Test"
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> depositService.deposit(request, accountNumber, email))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verifyNoInteractions(transactionReferenceGenerator, bankTransactionRepository, accountEntryRepository);
    }
}
