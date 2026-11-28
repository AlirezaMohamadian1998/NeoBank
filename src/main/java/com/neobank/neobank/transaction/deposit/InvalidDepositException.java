package com.neobank.neobank.transaction.deposit;

public class InvalidDepositException extends RuntimeException {
    public InvalidDepositException(String message) {
        super(message);
    }
}
