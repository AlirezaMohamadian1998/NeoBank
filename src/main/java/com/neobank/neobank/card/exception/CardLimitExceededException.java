package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CardLimitExceededException extends ApiException {
    public CardLimitExceededException(String message) {
        super(HttpStatus.CONFLICT, "Card limit exceeded", message);
    }
}
