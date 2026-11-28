package com.neobank.neobank.transaction.history;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidHistorySortException extends ApiException {
    public InvalidHistorySortException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid history sort", message);
    }
}
