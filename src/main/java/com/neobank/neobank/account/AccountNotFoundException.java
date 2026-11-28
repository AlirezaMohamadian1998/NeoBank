package com.neobank.neobank.account;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends ApiException {
    public AccountNotFoundException() {
        super(HttpStatus.NOT_FOUND, "Account not found", "Account not found");
    }
    
    public AccountNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "Account not found", message);
    }
}
