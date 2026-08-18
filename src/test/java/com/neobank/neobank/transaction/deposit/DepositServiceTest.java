package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.account.Account;
import com.neobank.neobank.account.AccountNotFoundException;
import com.neobank.neobank.account.AccountRepository;
import com.neobank.neobank.account.AccountType;
import com.neobank.neobank.customer.Customer;
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

import java.math.BigDecimal;
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

    private DepositService depositService;

    @Captor
    private ArgumentCaptor<BankTransaction> bankTransactionCaptor;

    @BeforeEach
    void setUp() {
        LedgerPostingService ledgerPostingService = new LedgerPostingService(referenceGenerator);
        depositService = new DepositService(
                accountRepository,
                bankTransactionRepository,
                referenceGenerator,
                ledgerPostingService
        );
    }

    @Test
    void depositCreditsOwnedAccountAndSavesCompletedTransactionWithEntry() {
        String accountNumber = "12345678900321";
        String email = "customer@example.com";
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

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.of(account));
        given(referenceGenerator.generate())
                .willReturn(transactionReference, entryReference);
        given(bankTransactionRepository.save(any(BankTransaction.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        DepositResponse response = depositService.deposit(request, accountNumber, email);

        verify(bankTransactionRepository).save(bankTransactionCaptor.capture());

        BankTransaction savedTransaction = bankTransactionCaptor.getValue();
        assertThat(savedTransaction.getEntries()).hasSize(1);
        LedgerEntry savedEntry = savedTransaction.getEntries().getFirst();

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

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(referenceGenerator, times(2)).generate();
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

        verifyNoInteractions(referenceGenerator, bankTransactionRepository);
    }
}
