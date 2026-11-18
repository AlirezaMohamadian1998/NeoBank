package com.neobank.neobank.card.exception;

public class CardPurchaseNotRefundableException extends RuntimeException {
    public CardPurchaseNotRefundableException(String message) {
        super(message);
    }
}
