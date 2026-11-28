package com.neobank.neobank.customer;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CustomerNotFoundException extends ApiException {
    public CustomerNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "Customer not found", message);
    }
}
