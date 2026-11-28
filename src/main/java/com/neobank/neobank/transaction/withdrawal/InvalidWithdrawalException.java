package com.neobank.neobank.transaction.withdrawal;

public class InvalidWithdrawalException extends RuntimeException {
    public InvalidWithdrawalException(String message) {
        super(message);
    }
}
