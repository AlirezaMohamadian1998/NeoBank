package com.neobank.neobank.card;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCardStatusTransitionException extends ApiException {
    public InvalidCardStatusTransitionException(String message) {
        super(HttpStatus.CONFLICT, "Invalid card status transition", message);
    }
}
