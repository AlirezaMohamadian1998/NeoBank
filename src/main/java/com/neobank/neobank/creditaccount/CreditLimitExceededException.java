package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CreditLimitExceededException extends ApiException {
    public CreditLimitExceededException(String message) {
        super(HttpStatus.CONFLICT, "Credit limit exceeded", message);
    }
}
