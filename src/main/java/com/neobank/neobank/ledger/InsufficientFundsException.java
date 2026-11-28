package com.neobank.neobank.ledger;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends ApiException {
    public InsufficientFundsException() {
        super(HttpStatus.CONFLICT, "Insufficient funds", "Insufficient funds");
    }
}
