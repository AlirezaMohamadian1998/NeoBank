package com.neobank.neobank.idempotency;

public class IdempotencyKeyRaceException extends RuntimeException {
    public IdempotencyKeyRaceException(Throwable cause) {
        super("Concurrent use of the same idempotency key", cause);
    }
}
