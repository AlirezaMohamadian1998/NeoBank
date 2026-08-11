package com.neobank.neobank.shared.exception;

import com.neobank.neobank.customer.CustomerNotFoundException;
import com.neobank.neobank.customer.EmailAlreadyRegisteredException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Map<String, String> validationErrors = new HashMap<>();
        List<FieldError> errors = ex.getBindingResult().getFieldErrors();

        errors.forEach(error -> {
            String fieldName = error.getField();
            String validationMsg = error.getDefaultMessage();
            validationErrors.put(fieldName, validationMsg);
        });

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, "Validation failed for one or more fields.");
        problemDetail.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        problemDetail.setTitle("Validation failed");
        problemDetail.setProperty("errors", validationErrors);

        return new ResponseEntity<>(problemDetail, status);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> handleEmailAlreadyRegisteredException(
            EmailAlreadyRegisteredException ex,
            WebRequest webRequest
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setInstance(URI.create(webRequest.getDescription(false).replace("uri=", "")));
        problemDetail.setTitle("Email already registered");

        return new ResponseEntity<>(problemDetail, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(
            AuthenticationException ex,
            WebRequest req
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        problemDetail.setInstance(URI.create(req.getDescription(false).replace("uri=", "")));
        problemDetail.setTitle("Authentication failed");

        return new ResponseEntity<>(problemDetail, HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleCustomerNotFoundException(
            CustomerNotFoundException ex,
            WebRequest request
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setTitle("Customer not found");
        problemDetail.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));

        return new ResponseEntity<>(problemDetail, HttpStatus.NOT_FOUND);
    }
}
