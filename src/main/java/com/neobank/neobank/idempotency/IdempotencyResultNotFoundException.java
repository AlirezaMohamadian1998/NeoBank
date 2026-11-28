package com.neobank.neobank.idempotency;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class IdempotencyResultNotFoundException extends ApiException {
    public IdempotencyResultNotFoundException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "Idempotency result not found", message);
    }
}
