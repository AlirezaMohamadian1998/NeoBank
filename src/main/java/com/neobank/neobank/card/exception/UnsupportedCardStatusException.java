package com.neobank.neobank.card.exception;

public class UnsupportedCardStatusException extends RuntimeException {
    public UnsupportedCardStatusException(String message) {
        super(message);
    }
}
