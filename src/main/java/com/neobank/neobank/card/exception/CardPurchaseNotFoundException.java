package com.neobank.neobank.card.exception;

public class CardPurchaseNotFoundException extends RuntimeException {
    public CardPurchaseNotFoundException(String message) {
        super(message);
    }
}
