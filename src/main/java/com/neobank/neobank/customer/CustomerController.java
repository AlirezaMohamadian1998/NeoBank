package com.neobank.neobank.customer;

import com.neobank.neobank.customer.dto.CustomerResponse;
import com.neobank.neobank.customer.dto.RegisterCustomerRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;

    @PostMapping
    public ResponseEntity<CustomerResponse> register(@RequestBody @Valid RegisterCustomerRequest request) {
        return ResponseEntity.status(201).body(customerService.register(request));
    }

    @GetMapping("/me")
    public ResponseEntity<CustomerResponse> getCurrentCustomer(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(customerService.getCurrentCustomer(jwt.getSubject()));
    }
}
