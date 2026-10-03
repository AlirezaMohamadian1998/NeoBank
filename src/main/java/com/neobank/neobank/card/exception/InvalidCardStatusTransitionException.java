package com.neobank.neobank.card.exception;

public class InvalidCardStatusTransitionException extends RuntimeException {
    public InvalidCardStatusTransitionException(String message) {
        super(message);
    }
}
