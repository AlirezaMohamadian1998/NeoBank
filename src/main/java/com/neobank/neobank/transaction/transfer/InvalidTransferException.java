package com.neobank.neobank.transaction.transfer;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidTransferException extends ApiException {
    public InvalidTransferException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid transfer", message);
    }
}
