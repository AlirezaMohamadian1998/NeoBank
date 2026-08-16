package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.account.*;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.transaction.*;
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {
    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private AccountEntryRepository accountEntryRepository;

    @Mock
    private TransactionReferenceGenerator transactionReferenceGenerator;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransferService transferService;

    @Captor
    ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @Captor
    ArgumentCaptor<AccountEntry> accountEntryCaptor;

    @Test
    void transferDebitsSourceAndCreditsTargetForSameCurrencyAndPersistsTransactionAndEntries() {
        String reference = "7f3c8a21d9e64b5fa2c17e9084bd6a31";

        Customer sourceCustomer = createCustomer("source@example.com");
        Customer targetCustomer = createCustomer("target@example.com");

        Account sourceAccount = createAccount("12345678900987", CurrencyCode.TRY, sourceCustomer);
        Account targetAccount = createAccount("98765432100123", CurrencyCode.TRY, targetCustomer);

        TransferRequest request = createTransferRequest(targetAccount.getAccountNumber(), new BigDecimal("500.00"));

        sourceAccount.credit(new BigDecimal("1000.00"));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(targetAccount.getAccountNumber()))
                .willReturn(Optional.of(targetAccount));

        given(transactionReferenceGenerator.generate())
                .willReturn(reference);

        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(accountEntryRepository.save(any(AccountEntry.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail());

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());
        verify(accountEntryRepository, times(2)).save(accountEntryCaptor.capture());

        var savedTransaction = bankTransactionCaptor.getValue();
        List<AccountEntry> entries = accountEntryCaptor.getAllValues();

        var sourceEntry = entries.get(0);
        var targetEntry = entries.get(1);

        assertThat(response.transactionReference())
                .isEqualTo(reference);
        assertThat(response.transactionType())
                .isSameAs(TransactionType.TRANSFER);
        assertThat(response.sourceAccountNumber())
                .isEqualTo(sourceAccount.getAccountNumber());
        assertThat(response.destinationAccountNumber())
                .isEqualTo(targetAccount.getAccountNumber());
        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());
        assertThat(response.balanceAfter())
                .isEqualByComparingTo(sourceEntry.getBalanceAfter());
        assertThat(response.currency())
                .isSameAs(sourceEntry.getCurrency());
        assertThat(response.note())
                .isEqualTo(request.note());
        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());

        assertThat(savedTransaction.getReference())
                .isEqualTo(reference);
        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.TRANSFER);
        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(sourceAccount.getBalance());
        assertThat(sourceEntry.getEntryDirection())
                .isSameAs(EntryDirection.DEBIT);
        assertThat(sourceEntry.getCurrency())
                .isSameAs(sourceAccount.getCurrency());
        assertThat(sourceEntry.getAccount())
                .isSameAs(sourceAccount);
        assertThat(sourceEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(targetEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(targetEntry.getBalanceAfter())
                .isEqualByComparingTo(targetAccount.getBalance());
        assertThat(targetEntry.getEntryDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(targetEntry.getCurrency())
                .isSameAs(targetAccount.getCurrency());
        assertThat(targetEntry.getAccount())
                .isSameAs(targetAccount);
        assertThat(targetEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("500.00");
        assertThat(targetAccount.getBalance())
                .isEqualByComparingTo("500.00");
        assertThat(response.balanceAfter())
                .isEqualByComparingTo("500.00");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail());
        verify(accountRepository).findByAccountNumber(targetAccount.getAccountNumber());
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void transferThrowsAccountNotFoundExceptionWhenSourceAccountDoesNotExist() {
        String targetAccountNumber = "12345678900987";
        String sourceAccountNumber = "98765432100123";
        String email = "source@example.com";

        TransferRequest request = createTransferRequest(targetAccountNumber, new BigDecimal("500.00"));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request, sourceAccountNumber, email))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, email);
        verify(accountRepository, never()).findByAccountNumber(any(String.class));
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, accountEntryRepository, transactionReferenceGenerator);
    }

    @Test
    void transferThrowsInvalidTransferExceptionWhenSourceAndTargetHaveSameAccountNumber() {
        String sameAccountNumber = "12345678900987";

        Customer sourceCustomer = createCustomer("source@example.com");

        Account sourceAccount = createAccount(sameAccountNumber, CurrencyCode.TRY, sourceCustomer);

        TransferRequest request = createTransferRequest(sameAccountNumber, new BigDecimal("500.00"));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));

        assertThatThrownBy(() -> transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Source and destination accounts cannot be the same");

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail());
        verify(accountRepository, never()).findByAccountNumber(sameAccountNumber);
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, accountEntryRepository, transactionReferenceGenerator);
    }

    @Test
    void transferThrowsAccountNotFoundExceptionWhenTargetAccountDoesNotExist() {
        String targetAccountNumber = "98765432100123";

        Customer sourceCustomer = createCustomer("source@example.com");

        Account sourceAccount = createAccount("12345678900987", CurrencyCode.TRY, sourceCustomer);

        TransferRequest request = createTransferRequest(targetAccountNumber, new BigDecimal("500.00"));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(targetAccountNumber))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Destination account not found");

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail());
        verify(accountRepository).findByAccountNumber(request.destinationAccountNumber());
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, accountEntryRepository, transactionReferenceGenerator);
    }

    @Test
    void transferThrowsInvalidTransferExceptionWhenSourceAndTargetCurrenciesDiffer() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer targetCustomer = createCustomer("target@example.com");

        Account sourceAccount = createAccount("12345678900987", CurrencyCode.TRY, sourceCustomer);
        Account targetAccount = createAccount("98765432100123", CurrencyCode.USD, targetCustomer);

        sourceAccount.credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(targetAccount.getAccountNumber(), new BigDecimal("500.00"));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(targetAccount.getAccountNumber()))
                .willReturn(Optional.of(targetAccount));

        assertThatThrownBy(() -> transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessage("Source and destination accounts must be in the same currency");

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("1000.00");
        assertThat(targetAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail());
        verify(accountRepository).findByAccountNumber(targetAccount.getAccountNumber());
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, accountEntryRepository, transactionReferenceGenerator);
    }

    @Test
    void transferThrowsInsufficientFundsWhenSourceBalanceIsLessThanAmount() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer targetCustomer = createCustomer("target@example.com");

        Account sourceAccount = createAccount("12345678900987", CurrencyCode.TRY, sourceCustomer);
        Account targetAccount = createAccount("98765432100123", CurrencyCode.TRY, targetCustomer);

        sourceAccount.credit(new BigDecimal("100.00"));

        TransferRequest request = createTransferRequest(targetAccount.getAccountNumber(), new BigDecimal("500.00"));

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(targetAccount.getAccountNumber()))
                .willReturn(Optional.of(targetAccount));

        assertThatThrownBy(()-> transferService.transfer(request, sourceAccount.getAccountNumber(), sourceCustomer.getEmail()))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(sourceAccount.getBalance())
                .isEqualByComparingTo("100.00");
        assertThat(targetAccount.getBalance())
                .isEqualByComparingTo("0.00");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccount.getAccountNumber(), sourceCustomer.getEmail());
        verify(accountRepository).findByAccountNumber(targetAccount.getAccountNumber());
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(bankTransactionRepository, accountEntryRepository, transactionReferenceGenerator);
    }

    private Customer createCustomer(String email) {
        return Customer.createNew(
                email,
                "{bcrypt}raw-password123",
                "Ada Lovelace"
        );
    }

    private Account createAccount(
            String accountNumber,
            CurrencyCode currency,
            Customer customer
    ) {
        return Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                currency,
                customer
        );
    }

    private TransferRequest createTransferRequest(String destinationAccountNumber, BigDecimal amount) {
        return new TransferRequest(
                amount,
                destinationAccountNumber,
                "Test"
        );
    }
}
