package com.neobank.neobank.card.exception;

public class InvalidCreditCardPayoutException extends RuntimeException {
    public InvalidCreditCardPayoutException(String message) {
        super(message);
    }
}
