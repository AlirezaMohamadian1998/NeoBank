package com.neobank.neobank.card.exception;

public class InvalidCardLimitException extends RuntimeException {
    public InvalidCardLimitException(String message) {
        super(message);
    }
}
