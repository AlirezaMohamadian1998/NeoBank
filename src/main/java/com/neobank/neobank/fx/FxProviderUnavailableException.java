package com.neobank.neobank.fx;

import com.neobank.neobank.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class FxProviderUnavailableException extends ApiException {
    public FxProviderUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Fx provider unavailable", message);
    }

    public FxProviderUnavailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Fx provider unavailable", message, cause);
    }
}
