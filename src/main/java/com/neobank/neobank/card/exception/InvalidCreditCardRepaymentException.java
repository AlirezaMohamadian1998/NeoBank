package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCreditCardRepaymentException extends ApiException {
    public InvalidCreditCardRepaymentException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid credit card repayment", message);
    }
}
