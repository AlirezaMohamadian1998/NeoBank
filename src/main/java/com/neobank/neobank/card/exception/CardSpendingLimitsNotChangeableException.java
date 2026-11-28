package com.neobank.neobank.card.exception;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CardSpendingLimitsNotChangeableException extends ApiException {
    public CardSpendingLimitsNotChangeableException(String message) {
        super(HttpStatus.CONFLICT, "Card limits not changeable", message);
    }
}
