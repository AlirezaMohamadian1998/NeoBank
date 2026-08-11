package com.neobank.neobank.auth;

import com.neobank.neobank.customer.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomerUserDetailsService implements UserDetailsService {

    private final CustomerRepository customerRepository;

    @Override
    public UserDetails loadUserByUsername(@NonNull String username) throws UsernameNotFoundException {
        var customer = customerRepository.findByEmailIgnoreCase(username).orElseThrow(
                () -> new UsernameNotFoundException("Customer not found")
        );

        return new User(
                customer.getEmail(),
                customer.getPasswordHash(),
                Collections.emptyList()
        );
    }
}
