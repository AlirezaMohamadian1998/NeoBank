package com.neobank.neobank.card.limit;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCardLimitException extends ApiException {
    public InvalidCardLimitException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid card limit", message);
    }
}
