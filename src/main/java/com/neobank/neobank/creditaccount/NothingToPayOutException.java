package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class NothingToPayOutException extends ApiException {
    public NothingToPayOutException(String message) {
        super(HttpStatus.CONFLICT, "Nothing to pay out", message);
    }
}
