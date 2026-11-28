package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CardExpiredException extends ApiException {
    public CardExpiredException(String message) {
        super(HttpStatus.CONFLICT, "Card expired", message);
    }
}
