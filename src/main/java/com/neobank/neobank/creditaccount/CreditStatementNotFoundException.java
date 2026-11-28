package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CreditStatementNotFoundException extends ApiException {
    public CreditStatementNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "Credit statement not found", message);
    }
}
