package com.neobank.neobank.idempotency;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class IdempotencyConflictException extends ApiException {
    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, "Idempotency Conflict", message);
    }
}
