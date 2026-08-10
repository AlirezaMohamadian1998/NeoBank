package com.neobank.neobank.customer;

import com.neobank.neobank.customer.dto.CustomerResponse;
import com.neobank.neobank.customer.dto.RegisterCustomerRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;

    @PostMapping
    public ResponseEntity<CustomerResponse> register(@RequestBody @Valid RegisterCustomerRequest request) {
        return ResponseEntity.status(201).body(customerService.register(request));
    }
}
