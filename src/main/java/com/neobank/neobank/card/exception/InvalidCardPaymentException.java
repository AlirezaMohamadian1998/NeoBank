package com.neobank.neobank.card.exception;

public class InvalidCardPaymentException extends RuntimeException {
    public InvalidCardPaymentException(String message) {
        super(message);
    }
}