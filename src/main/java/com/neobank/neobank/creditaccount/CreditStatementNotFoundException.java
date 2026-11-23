package com.neobank.neobank.creditaccount;

public class CreditStatementNotFoundException extends RuntimeException {
    public CreditStatementNotFoundException(String message) {
        super(message);
    }
}
