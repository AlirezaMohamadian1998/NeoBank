package com.neobank.neobank.transaction.transfer;

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
import com.neobank.neobank.transaction.transfer.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String SOURCE_LEDGER_REFERENCE = "1f3c8a21d9e64b5fa2c17e9084bd6a35";
    private static final String DESTINATION_LEDGER_REFERENCE = "2f3c8a21d9e64b5fa2c17e9084bd6a36";
    private static final String TRANSACTION_REFERENCE = "7f3c8a21d9e64b5fa2c17e9084bd6a31";
    private static final String SOURCE_ENTRY_REFERENCE = "9f3c8a21d9e64b5fa2c17e9084bd6a33";
    private static final String DESTINATION_ENTRY_REFERENCE = "af3c8a21d9e64b5fa2c17e9084bd6a34";

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private ReferenceGenerator referenceGenerator;

    @Mock
    private AccountRepository accountRepository;

    private TransferService transferService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        transferService = new TransferService(
                bankTransactionRepository,
                referenceGenerator,
                accountRepository,
                ledgerPostingService
        );
    }

    @Test
    void transferDebitsSourceAndCreditsDestinationAndSavesCompletedTransactionWithBothEntries() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");

        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.TRY
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));
        given(referenceGenerator.generate())
                .willReturn(
                        TRANSACTION_REFERENCE,
                        SOURCE_ENTRY_REFERENCE,
                        DESTINATION_ENTRY_REFERENCE
                );
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var response = transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();
        List<LedgerEntry> entries = savedTransaction.getEntries();

        assertThat(entries).hasSize(2);
        LedgerEntry sourceEntry = entries.get(0);
        LedgerEntry destinationEntry = entries.get(1);

        assertThat(savedTransaction.getReference())
                .isEqualTo(TRANSACTION_REFERENCE);
        assertThat(savedTransaction.getTransactionType())
                .isSameAs(TransactionType.TRANSFER);
        assertThat(savedTransaction.getStatus())
                .isSameAs(TransactionStatus.COMPLETED);
        assertThat(savedTransaction.getRequestedAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(savedTransaction.getRequestedCurrency())
                .isSameAs(request.currency());
        assertThat(savedTransaction.getNote())
                .isEqualTo(request.note());

        assertThat(sourceEntry.getReference())
                .isEqualTo(SOURCE_ENTRY_REFERENCE);
        assertThat(sourceEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(sourceEntry.getBalanceAfter())
                .isEqualByComparingTo(sourceAccount.getBalance());
        assertThat(sourceEntry.getDirection())
                .isSameAs(EntryDirection.DEBIT);
        assertThat(sourceEntry.getCurrency())
                .isSameAs(sourceAccount.getCurrency());
        assertThat(sourceEntry.getLedgerAccount())
                .isSameAs(sourceAccount.getLedgerAccount());
        assertThat(sourceEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(destinationEntry.getReference())
                .isEqualTo(DESTINATION_ENTRY_REFERENCE);
        assertThat(destinationEntry.getAmount())
                .isEqualByComparingTo(request.amount());
        assertThat(destinationEntry.getBalanceAfter())
                .isEqualByComparingTo(destinationAccount.getBalance());
        assertThat(destinationEntry.getDirection())
                .isSameAs(EntryDirection.CREDIT);
        assertThat(destinationEntry.getCurrency())
                .isSameAs(destinationAccount.getCurrency());
        assertThat(destinationEntry.getLedgerAccount())
                .isSameAs(destinationAccount.getLedgerAccount());
        assertThat(destinationEntry.getBankTransaction())
                .isSameAs(savedTransaction);

        assertThat(response.transactionReference())
                .isEqualTo(savedTransaction.getReference());
        assertThat(response.sourceEntryReference())
                .isEqualTo(sourceEntry.getReference());
        assertThat(response.transactionType())
                .isSameAs(TransactionType.TRANSFER);
        assertThat(response.sourceAccountNumber())
                .isEqualTo(sourceAccount.getAccountNumber());
        assertThat(response.destinationAccountNumber())
                .isEqualTo(destinationAccount.getAccountNumber());
        assertThat(response.amount())
                .isEqualByComparingTo(request.amount());
        assertThat(response.sourceBalanceAfter())
                .isEqualByComparingTo(sourceEntry.getBalanceAfter());
        assertThat(response.currency())
                .isSameAs(request.currency());
        assertThat(response.note())
                .isEqualTo(request.note());
        assertThat(response.createdAt())
                .isEqualTo(savedTransaction.getCreatedAt());

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("500.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("500.00");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository).findByAccountNumber(destinationAccount.getAccountNumber());
        verify(referenceGenerator, times(3)).generate();
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void transferThrowsAccountNotFoundExceptionWhenSourceAccountDoesNotExist() {
        String destinationAccountNumber = "12345678900987";
        String sourceAccountNumber = "98765432100123";
        String email = "source@example.com";

        TransferRequest request = createTransferRequest(
                destinationAccountNumber,
                new BigDecimal("500.00"),
                CurrencyCode.TRY
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(request, sourceAccountNumber, email))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(sourceAccountNumber, email);
        verify(accountRepository, never()).findByAccountNumber(any(String.class));
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(bankTransactionRepository, referenceGenerator);
    }

    @Test
    void transferThrowsInvalidTransferExceptionWhenSourceAndDestinationHaveSameAccountNumber() {
        String sameAccountNumber = "12345678900987";
        Customer sourceCustomer = createCustomer("source@example.com");
        Account sourceAccount = createAccount(
                sameAccountNumber,
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );

        TransferRequest request = createTransferRequest(
                sameAccountNumber,
                new BigDecimal("500.00"),
                CurrencyCode.TRY
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).isInstanceOf(InvalidTransferException.class)
                .hasMessage("Source and destination accounts cannot be the same");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository, never()).findByAccountNumber(sameAccountNumber);
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(bankTransactionRepository, referenceGenerator);
    }

    @Test
    void transferThrowsAccountNotFoundExceptionWhenDestinationAccountDoesNotExist() {
        String destinationAccountNumber = "98765432100123";
        Customer sourceCustomer = createCustomer("source@example.com");
        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );

        TransferRequest request = createTransferRequest(
                destinationAccountNumber,
                new BigDecimal("500.00"),
                CurrencyCode.TRY
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccountNumber))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Destination account not found");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository).findByAccountNumber(request.destinationAccountNumber());
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(bankTransactionRepository, referenceGenerator);
    }

    @Test
    void transferThrowsInvalidTransferExceptionWhenSourceAndDestinationCurrenciesDiffer() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");
        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.USD,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.TRY
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).isInstanceOf(InvalidTransferException.class)
                .hasMessage("Source and destination accounts must be in the same currency as request");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository).findByAccountNumber(destinationAccount.getAccountNumber());
        verify(accountRepository, never()).save(any(Account.class));
        verifyNoInteractions(bankTransactionRepository, referenceGenerator);
    }

    @Test
    void transferThrowsInvalidTransferExceptionWhenRequestCurrencyDiffersFromAccountCurrency() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");
        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("1000.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.USD
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).isInstanceOf(InvalidTransferException.class)
                .hasMessage("Source and destination accounts must be in the same currency as request");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("0.00");
        verifyNoInteractions(bankTransactionRepository, referenceGenerator);
        verify(accountRepository, never()).save(any(Account.class));
    }

    @Test
    void transferThrowsInsufficientFundsWhenSourceBalanceIsLessThanAmount() {
        Customer sourceCustomer = createCustomer("source@example.com");
        Customer destinationCustomer = createCustomer("target@example.com");
        Account sourceAccount = createAccount(
                "12345678900987",
                SOURCE_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                sourceCustomer
        );
        Account destinationAccount = createAccount(
                "98765432100123",
                DESTINATION_LEDGER_REFERENCE,
                CurrencyCode.TRY,
                destinationCustomer
        );
        sourceAccount.getLedgerAccount().credit(new BigDecimal("100.00"));

        TransferRequest request = createTransferRequest(
                destinationAccount.getAccountNumber(),
                new BigDecimal("500.00"),
                CurrencyCode.TRY
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).willReturn(Optional.of(sourceAccount));
        given(accountRepository.findByAccountNumber(destinationAccount.getAccountNumber()))
                .willReturn(Optional.of(destinationAccount));
        given(referenceGenerator.generate())
                .willReturn(TRANSACTION_REFERENCE, SOURCE_ENTRY_REFERENCE);

        assertThatThrownBy(() -> transferService.transfer(
                request,
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        )).isInstanceOf(InsufficientFundsException.class)
                .hasMessage("Insufficient funds");

        assertThat(sourceAccount.getBalance()).isEqualByComparingTo("100.00");
        assertThat(destinationAccount.getBalance()).isEqualByComparingTo("0.00");
        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(
                sourceAccount.getAccountNumber(),
                sourceCustomer.getEmail()
        );
        verify(accountRepository).findByAccountNumber(destinationAccount.getAccountNumber());
        verify(referenceGenerator, times(2)).generate();
        verifyNoInteractions(bankTransactionRepository);
        verify(accountRepository, never()).save(any(Account.class));
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
            String ledgerReference,
            CurrencyCode currency,
            Customer customer
    ) {
        LedgerAccount ledgerAccount = LedgerAccount.createNew(
                ledgerReference,
                LedgerAccountType.LIABILITY,
                currency
        );

        return Account.createNew(
                accountNumber,
                "Private Account",
                AccountType.CURRENT,
                customer,
                ledgerAccount
        );
    }

    private TransferRequest createTransferRequest(
            String destinationAccountNumber,
            BigDecimal amount,
            CurrencyCode currency
    ) {
        return new TransferRequest(
                amount,
                destinationAccountNumber,
                "Test",
                currency
        );
    }
}
