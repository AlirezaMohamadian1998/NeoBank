package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CreditAccountClosedException extends ApiException {
    public CreditAccountClosedException(String message) {
        super(HttpStatus.CONFLICT, "Credit account closed", message);
    }
}
