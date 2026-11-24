package com.neobank.neobank.card.exception;

public class InvalidCreditCardClosingException extends RuntimeException {
    public InvalidCreditCardClosingException(String message) {
        super(message);
    }
}
