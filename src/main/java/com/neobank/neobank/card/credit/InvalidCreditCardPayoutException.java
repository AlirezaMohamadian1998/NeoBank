package com.neobank.neobank.card.credit;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCreditCardPayoutException extends ApiException {
    public InvalidCreditCardPayoutException(String message) {
        super(HttpStatus.BAD_REQUEST, "Invalid credit card payout", message);
    }
}
