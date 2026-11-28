package com.neobank.neobank.fx;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class FxRateLockUnavailableException extends ApiException {
    public FxRateLockUnavailableException(String message) {
        super(HttpStatus.CONFLICT, "Fx rate lock unavailable", message);
    }
}
