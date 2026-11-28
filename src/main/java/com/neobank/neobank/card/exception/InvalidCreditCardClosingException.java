package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCreditCardClosingException extends ApiException {
    public InvalidCreditCardClosingException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid credit card closing", message);
    }
}
