package com.neobank.neobank.creditaccount;

public class RepaymentExceedsBalanceException extends RuntimeException {
    public RepaymentExceedsBalanceException(String message) {
        super(message);
    }
}
