package com.neobank.neobank.customer;

import com.neobank.neobank.customer.dto.CustomerResponse;
import com.neobank.neobank.customer.dto.RegisterCustomerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private CustomerService customerService;

    @Test
    void registerSavesCustomerWithEncodedPasswordWhenEmailIsAvailable() {
        RegisterCustomerRequest request = new RegisterCustomerRequest(
                " Customer@Example.COM ",
                "raw-password-123",
                " Ada Lovelace "
        );
        String encodedPassword = "{bcrypt}encoded-password";

        given(customerRepository.existsByEmailIgnoreCase("Customer@Example.COM"))
                .willReturn(false);
        given(passwordEncoder.encode(request.password()))
                .willReturn(encodedPassword);
        given(customerRepository.save(any(Customer.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        CustomerResponse response = customerService.register(request);

        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(customerCaptor.capture());

        Customer savedCustomer = customerCaptor.getValue();
        assertThat(savedCustomer.getEmail()).isEqualTo("customer@example.com");
        assertThat(savedCustomer.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(savedCustomer.getPasswordHash())
                .isEqualTo(encodedPassword)
                .isNotEqualTo(request.password());

        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.fullName()).isEqualTo("Ada Lovelace");

        verify(customerRepository).existsByEmailIgnoreCase("Customer@Example.COM");
        verify(passwordEncoder).encode(request.password());
    }

    @Test
    void registerRejectsDuplicateEmailWithoutEncodingOrSaving() {
        RegisterCustomerRequest request = new RegisterCustomerRequest(
                "customer@example.com",
                "raw-password-123",
                "Ada Lovelace"
        );

        given(customerRepository.existsByEmailIgnoreCase(request.email()))
                .willReturn(true);

        assertThatThrownBy(() -> customerService.register(request))
                .isInstanceOf(EmailAlreadyRegisteredException.class)
                .hasMessage("Email already registered");

        verify(passwordEncoder, never()).encode(any());
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void getCurrentCustomerReturnsMappedCustomerWhenEmailExists() {
        String email = "customer@example.com";
        Customer customer = Customer.createNew(email, "{bcrypt}encoded-password", "Ada Lovelace");

        given(customerRepository.findByEmailIgnoreCase(email))
                .willReturn(Optional.of(customer));

        CustomerResponse response = customerService.getCurrentCustomer(email);

        assertThat(response.email()).isEqualTo(email);
        assertThat(response.fullName()).isEqualTo("Ada Lovelace");

        verify(customerRepository).findByEmailIgnoreCase(email);
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void getCurrentCustomerThrowsCustomerNotFoundExceptionWhenEmailDoesNotExist() {
        String email = "customer@example.com";
        given(customerRepository.findByEmailIgnoreCase(email))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getCurrentCustomer(email))
                .isExactlyInstanceOf(CustomerNotFoundException.class)
                .hasMessage("Customer not found");

        verify(customerRepository).findByEmailIgnoreCase(email);
        verifyNoInteractions(passwordEncoder);
    }
}
