package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CreditCardNotFoundException extends ApiException {
    public CreditCardNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "Credit card not found", message);
    }
}
