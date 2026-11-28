package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class DebitCardNotFoundException extends ApiException {
    public DebitCardNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "Debit card not found", message);
    }
}
