package com.neobank.neobank.account;

import com.neobank.neobank.account.dto.CreateAccountRequest;
import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.CustomerRepository;
import com.neobank.neobank.shared.money.CurrencyCode;
import com.neobank.neobank.shared.reference.ReferenceGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
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

    @Mock
    private ReferenceGenerator referenceGenerator;

    @InjectMocks
    private AccountService accountService;

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

        verifyNoInteractions(accountNumberGenerator, accountRepository, referenceGenerator);
    }
}
