package com.neobank.neobank.customer;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class EmailAlreadyRegisteredException extends ApiException {
    public EmailAlreadyRegisteredException(String message) {
        super(HttpStatus.CONFLICT, "Email already registered", message);
    }
}
