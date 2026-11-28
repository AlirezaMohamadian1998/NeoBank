package com.neobank.neobank.transaction.withdrawal;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidWithdrawalException extends ApiException {
    public InvalidWithdrawalException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid withdrawal", message);
    }
}
