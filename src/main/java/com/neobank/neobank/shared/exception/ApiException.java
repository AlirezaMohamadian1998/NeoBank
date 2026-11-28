package com.neobank.neobank.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String title;

    protected ApiException(HttpStatus status, String title, String message) {
        super(message);
        this.status = status;
        this.title = title;
    }

    protected ApiException(HttpStatus status, String title, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.title = title;
    }
}
