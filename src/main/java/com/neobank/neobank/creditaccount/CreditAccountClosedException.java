package com.neobank.neobank.creditaccount;

public class CreditAccountClosedException extends RuntimeException {
    public CreditAccountClosedException(String message) {
        super(message);
    }
}
