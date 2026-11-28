package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CardPurchaseNotRefundableException extends ApiException {
    public CardPurchaseNotRefundableException(String message) {
        super(HttpStatus.CONFLICT, "Card purchase not refundable", message);
    }
}
