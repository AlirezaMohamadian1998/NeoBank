package com.neobank.neobank.card.payment;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCardPaymentException extends ApiException {
    public InvalidCardPaymentException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid card payment", message);
    }
}