package com.neobank.neobank.creditaccount;

public class NothingToPayOutException extends RuntimeException {
    public NothingToPayOutException(String message) {
        super(message);
    }
}
