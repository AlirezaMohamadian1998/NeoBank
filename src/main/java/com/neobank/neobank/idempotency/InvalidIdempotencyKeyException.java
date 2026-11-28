package com.neobank.neobank.idempotency;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidIdempotencyKeyException extends ApiException {
    public InvalidIdempotencyKeyException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid idempotency key", message);
    }
}
