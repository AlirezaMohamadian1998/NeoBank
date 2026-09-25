package com.neobank.neobank.card;

public class DebitCardNotFoundException extends RuntimeException {
    public DebitCardNotFoundException(String message) {
        super(message);
    }
}
