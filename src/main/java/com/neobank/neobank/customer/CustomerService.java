package com.neobank.neobank.customer;

import com.neobank.neobank.customer.dto.CustomerResponse;
import com.neobank.neobank.customer.dto.RegisterCustomerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public CustomerResponse register(RegisterCustomerRequest request) {
        var exists = customerRepository.existsByEmailIgnoreCase(request.email().trim());
        if (exists) {
            throw new EmailAlreadyRegisteredException("Email already registered");
        }

        var passwordHash = passwordEncoder.encode(request.password());
        var customer = CustomerMapper.toCustomerEntity(request, passwordHash);

        return CustomerMapper.toResponse(customerRepository.save(customer));
    }
}
