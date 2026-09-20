package com.neobank.neobank.card;

public class CardExpiredException extends RuntimeException {
    public CardExpiredException(String message) {
        super(message);
    }
}
