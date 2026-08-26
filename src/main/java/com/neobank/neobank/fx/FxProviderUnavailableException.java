package com.neobank.neobank.fx;

public class FxProviderUnavailableException extends RuntimeException {
    public FxProviderUnavailableException(String message) {
        super(message);
    }

    public FxProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
