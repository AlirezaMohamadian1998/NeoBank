package com.neobank.neobank.card.exception;

public class DebitCardNotFoundException extends RuntimeException {
    public DebitCardNotFoundException(String message) {
        super(message);
    }
}
