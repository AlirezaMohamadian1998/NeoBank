package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CardNotActiveException extends ApiException {
    public CardNotActiveException(String message) {
        super(HttpStatus.CONFLICT, "Card not active", message);
    }
}
