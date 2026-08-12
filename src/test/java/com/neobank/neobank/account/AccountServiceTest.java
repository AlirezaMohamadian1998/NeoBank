package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.AccountResponse;
import com.neobank.neobank.account.dto.CreateAccountRequest;
import com.neobank.neobank.customer.Customer;
import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.CustomerRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private AccountService accountService;

    @Captor
    private ArgumentCaptor<Account> accountCaptor;

    @Test
    void createAccountSavesZeroBalanceAccountForAuthenticatedCustomer() {
        CreateAccountRequest request = new CreateAccountRequest(
                "  Private Account  ",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        String accountNumber = "12345678900987";

        given(customerRepository.findByEmailIgnoreCase("customer@example.com"))
                .willReturn(Optional.of(customer));
        given(accountNumberGenerator.generate())
                .willReturn(accountNumber);
        given(accountRepository.existsByAccountNumber(accountNumber))
                .willReturn(false);
        given(accountRepository.save(any(Account.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.createAccount(request, customer.getEmail());
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();

        assertThat(savedAccount.getAccountNumber())
                .isEqualTo(accountNumber);
        assertThat(savedAccount.getBalance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(savedAccount.getName())
                .isEqualTo("Private Account");
        assertThat(savedAccount.getAccountType())
                .isEqualTo(AccountType.CURRENT);
        assertThat(savedAccount.getCurrency())
                .isEqualTo(CurrencyCode.TRY);
        assertThat(savedAccount.getCustomer())
                .isSameAs(customer);

        assertThat(response.accountNumber())
                .isEqualTo(accountNumber);
        assertThat(response.balance())
                .isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(response.name())
                .isEqualTo("Private Account");
        assertThat(response.accountType())
                .isEqualTo(AccountType.CURRENT);
        assertThat(response.currency())
                .isEqualTo(CurrencyCode.TRY);

        verify(accountNumberGenerator, times(1))
                .generate();
        verify(customerRepository)
                .findByEmailIgnoreCase(customer.getEmail());
        verify(accountRepository)
                .existsByAccountNumber(accountNumber);
        verify(accountRepository, times(1))
                .save(any(Account.class));
    }

    @Test
    void createAccountRetriesWhenGeneratedAccountNumberAlreadyExists() {
        CreateAccountRequest request = new CreateAccountRequest(
                "  Private Account  ",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        String accountNumber1 = "12345678900987";
        String accountNumber2 = "09876543211234";

        given(accountNumberGenerator.generate())
                .willReturn(accountNumber1, accountNumber2);
        given(accountRepository.existsByAccountNumber(accountNumber1))
                .willReturn(true);
        given(accountRepository.existsByAccountNumber(accountNumber2))
                .willReturn(false);
        given(customerRepository.findByEmailIgnoreCase("customer@example.com"))
                .willReturn(Optional.of(customer));
        given(accountRepository.save(any(Account.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.createAccount(request, customer.getEmail());
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();

        assertThat(response.accountNumber())
                .isEqualTo(accountNumber2);
        assertThat(savedAccount.getAccountNumber())
                .isEqualTo(accountNumber2);

        verify(accountNumberGenerator, times(2))
                .generate();
        verify(accountRepository)
                .existsByAccountNumber(accountNumber1);
        verify(accountRepository)
                .existsByAccountNumber(accountNumber2);
        verify(accountRepository, times(1))
                .save(any(Account.class));
    }

    @Test
    void createAccountThrowsCustomerNotFoundExceptionWhenCustomerDoesNotExist() {
        CreateAccountRequest request = new CreateAccountRequest(
                "  Private Account  ",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        given(customerRepository.findByEmailIgnoreCase("missing@example.com"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.createAccount(request, "missing@example.com"))
                .isExactlyInstanceOf(CustomerNotFoundException.class)
                .hasMessage("Customer not found");

        verify(customerRepository)
                .findByEmailIgnoreCase("missing@example.com");

        verifyNoInteractions(accountNumberGenerator, accountRepository);
    }

    @Test
    void createAccountThrowsIllegalStateExceptionAfterMaximumAccountNumberCollisions() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        CreateAccountRequest request = new CreateAccountRequest(
                "  Private Account  ",
                AccountType.CURRENT,
                CurrencyCode.TRY
        );

        given(customerRepository.findByEmailIgnoreCase(any(String.class)))
                .willReturn(Optional.of(customer));
        given(accountNumberGenerator.generate())
                .willReturn("12345678900987");
        given(accountRepository.existsByAccountNumber(any(String.class)))
                .willReturn(true);

        assertThatThrownBy(() -> accountService.createAccount(request, customer.getEmail()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to generate unique account number");

        verify(accountNumberGenerator, times(10))
                .generate();
        verify(customerRepository)
                .findByEmailIgnoreCase(customer.getEmail());
        verify(accountRepository, times(10))
                .existsByAccountNumber(any(String.class));
        verify(accountRepository, never())
                .save(any(Account.class));
    }

    @Test
    void getCurrentCustomerAccountsReturnsMappedAccountsInRepositoryOrder() {
        String email = "customer@example.com";

        Customer customer = Customer.createNew(
                email,
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        Account account1 = Account.createNew(
                "12345678900987",
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY,
                customer
        );

        Account account2 = Account.createNew(
                "98765432101234",
                "Saving Account",
                AccountType.SAVINGS,
                CurrencyCode.USD,
                customer
        );

        given(accountRepository.findAllByCustomer_EmailIgnoreCaseOrderByCreatedAtDesc(email))
                .willReturn(List.of(account1, account2));

        List<AccountResponse> response = accountService.getCurrentCustomerAccounts(email);

        assertThat(response.size())
                .isEqualTo(2);
        assertThat(response.getFirst())
                .isEqualTo(AccountMapper.toResponse(account1));
        assertThat(response.getLast())
                .isEqualTo(AccountMapper.toResponse(account2));

        assertThat(response.getFirst().accountNumber())
                .isEqualTo("12345678900987");
        assertThat(response.getFirst().name())
                .isEqualTo("Private Account");
        assertThat(response.getFirst().accountType())
                .isSameAs(AccountType.CURRENT);
        assertThat(response.getFirst().currency())
                .isSameAs(CurrencyCode.TRY);
        assertThat(response.getFirst().balance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        assertThat(response.getLast().accountNumber())
                .isEqualTo("98765432101234");
        assertThat(response.getLast().name())
                .isEqualTo("Saving Account");
        assertThat(response.getLast().accountType())
                .isSameAs(AccountType.SAVINGS);
        assertThat(response.getLast().currency())
                .isSameAs(CurrencyCode.USD);
        assertThat(response.getLast().balance())
                .isEqualByComparingTo(new BigDecimal("0.00"));

        verify(accountRepository)
                .findAllByCustomer_EmailIgnoreCaseOrderByCreatedAtDesc(email);
        verify(accountRepository, never())
                .save(any(Account.class));

        verifyNoInteractions(accountNumberGenerator);
        verifyNoInteractions(customerRepository);
    }

    @Test
    void getCurrentCustomerAccountsReturnsEmptyListWhenCustomerHasNoAccounts() {
        String email = "customer@example.com";

        given(accountRepository.findAllByCustomer_EmailIgnoreCaseOrderByCreatedAtDesc(email))
                .willReturn(List.of());

        List<AccountResponse> response = accountService.getCurrentCustomerAccounts(email);

        assertThat(response).isEmpty();

        verify(accountRepository)
                .findAllByCustomer_EmailIgnoreCaseOrderByCreatedAtDesc(email);
        verify(accountRepository, never())
                .save(any(Account.class));

        verifyNoInteractions(accountNumberGenerator);
        verifyNoInteractions(customerRepository);
    }

    @Test
    void getCurrentCustomerAccountReturnsMappedOwnedAccount() {
        Customer customer = Customer.createNew(
                "customer@example.com",
                "{bcrypt}encoded-password",
                "Ada Lovelace"
        );

        Account account = Account.createNew(
                "12345678900123",
                "Private Account",
                AccountType.CURRENT,
                CurrencyCode.TRY,
                customer
        );

        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(account.getAccountNumber(), customer.getEmail()))
                .willReturn(Optional.of(account));

        AccountResponse response = accountService.getCurrentCustomerAccount(account.getAccountNumber(), customer.getEmail());

        assertThat(response.accountNumber())
                .isEqualTo(account.getAccountNumber());
        assertThat(response.name())
                .isEqualTo(account.getName());
        assertThat(response.accountType())
                .isSameAs(account.getAccountType());
        assertThat(response.currency())
                .isSameAs(account.getCurrency());
        assertThat(response.balance())
                .isEqualByComparingTo(account.getBalance());

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(account.getAccountNumber(), customer.getEmail());
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(accountNumberGenerator, customerRepository);
    }

    @Test
    void getCurrentCustomerAccountThrowsAccountNotFoundExceptionWhenOwnedAccountDoesNotExist() {
        String email = "customer@example.com";
        String accountNumber = "12345678900123";
        given(accountRepository.findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getCurrentCustomerAccount(accountNumber, email))
                .isExactlyInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");

        verify(accountRepository).findByAccountNumberAndCustomer_EmailIgnoreCase(accountNumber, email);
        verify(accountRepository, never()).save(any(Account.class));

        verifyNoInteractions(accountNumberGenerator, customerRepository);
    }
}
