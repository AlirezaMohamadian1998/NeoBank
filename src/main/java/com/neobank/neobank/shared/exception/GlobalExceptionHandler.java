package com.neobank.neobank.shared.exception;

import com.neobank.neobank.card.exception.CardDailySpendRaceException;
import com.neobank.neobank.idempotency.IdempotencyKeyRaceException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.*;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Map<String, String> validationErrors = new HashMap<>();
        boolean hasFieldErrors = false;

        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                List<FieldError> errors = parameterErrors.getFieldErrors();
                hasFieldErrors |= !errors.isEmpty();

                errors.forEach(error -> validationErrors.put(error.getField(), error.getDefaultMessage()));
                continue;
            }

            String parameterName = result.getMethodParameter().getParameterName();

            result.getResolvableErrors().stream()
                    .findFirst()
                    .ifPresent(error -> validationErrors.put(parameterName, error.getDefaultMessage()));
        }

        String detail = hasFieldErrors
                ? "Validation failed for one or more fields."
                : "Validation failed for one or more parameters";

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        problemDetail.setTitle("Validation failed");
        problemDetail.setProperty("errors", validationErrors);

        return new ResponseEntity<>(problemDetail, status);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(
            ApiException ex,
            HttpServletRequest request
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setTitle(ex.getTitle());

        return ResponseEntity.status(ex.getStatus()).body(problemDetail);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        problemDetail.setTitle("Authentication failed");

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problemDetail);
    }

    @ExceptionHandler(IdempotencyKeyRaceException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyRace(
            HttpServletRequest request
    ) {
        return retryLater(
                "Idempotency request in progress",
                "The idempotency key is currently being processed. Retry the same request using the same key.",
                request
        );
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrencyFailure(
            HttpServletRequest request
    ) {
        return retryLater(
                "Temporary concurrency failure",
                "The request could not be completed because of a temporary database concurrency conflict. Please retry.",
                request
        );
    }

    @ExceptionHandler(CardDailySpendRaceException.class)
    public ResponseEntity<ProblemDetail> handleCardDailySpendRaceException(
            HttpServletRequest request
    ) {
        return retryLater(
                "Card purchase in progress",
                "Another purchase on this card is being processed. Retry the same request using the same key.",
                request
        );
    }

    private ResponseEntity<ProblemDetail> retryLater(String title, String detail, HttpServletRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problemDetail.setTitle(title);
        problemDetail.setInstance(URI.create(request.getRequestURI()));

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problemDetail);
    }
}
