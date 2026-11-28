package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CardNotFoundException extends ApiException {
    public CardNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "Card not found", message);
    }
}
