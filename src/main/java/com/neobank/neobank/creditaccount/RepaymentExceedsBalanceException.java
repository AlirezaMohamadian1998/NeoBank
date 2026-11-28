package com.neobank.neobank.creditaccount;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class RepaymentExceedsBalanceException extends ApiException {
    public RepaymentExceedsBalanceException(String message) {
        super(HttpStatus.CONFLICT, "Repayment exceeds balance", message);
    }
}
