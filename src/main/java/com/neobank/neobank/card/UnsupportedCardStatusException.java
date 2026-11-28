package com.neobank.neobank.card;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class UnsupportedCardStatusException extends ApiException {
    public UnsupportedCardStatusException(String message) {
        super(HttpStatus.BAD_REQUEST, "Unsupported card status", message);
    }
}
