package com.neobank.neobank.idempotency;

public class IdempotencyResultNotFoundException extends RuntimeException {
    public IdempotencyResultNotFoundException(String message) {
        super(message);
    }
}
