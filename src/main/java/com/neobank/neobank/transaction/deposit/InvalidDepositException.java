package com.neobank.neobank.transaction.deposit;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidDepositException extends ApiException {
    public InvalidDepositException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid deposit", message);
    }
}
