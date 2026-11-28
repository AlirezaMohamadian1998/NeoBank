package com.neobank.neobank.card.payment;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CardPurchaseNotFoundException extends ApiException {
    public CardPurchaseNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "Card purchase not found", message);
    }
}
