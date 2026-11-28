package com.neobank.neobank.card.credit;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PayoffQuoteExpiredException extends ApiException {
    public PayoffQuoteExpiredException(String message) {
        super(HttpStatus.CONFLICT, "Payoff quote expired", message);
    }
}
