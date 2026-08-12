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
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
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
}
