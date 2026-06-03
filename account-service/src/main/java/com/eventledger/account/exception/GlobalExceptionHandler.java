package com.eventledger.account.exception;

import com.eventledger.account.dto.ErrorResponse;
import com.eventledger.account.dto.FieldError;
import com.eventledger.account.dto.ValidationErrorResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ValidationErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        log.warn("Validation failed: {}", errors);
        return new ValidationErrorResponse(errors);
    }

    // Handles enum ("type") and OffsetDateTime ("eventTimestamp") deserialization failures —
    // these arrive as InvalidFormatException BEFORE Bean Validation runs, so we must
    // special-case them to return the same {"errors":[...]} shape clients expect.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Object handleUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException ife && ife.getTargetType() != null) {
            String field = ife.getPath().isEmpty() ? ""
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            if (ife.getTargetType().isEnum()) {
                return new ValidationErrorResponse(
                        List.of(new FieldError("type", "type must be CREDIT or DEBIT")));
            }
            if (OffsetDateTime.class.equals(ife.getTargetType())) {
                return new ValidationErrorResponse(List.of(new FieldError(field,
                        "eventTimestamp must be a valid ISO 8601 datetime")));
            }
        }
        log.warn("Malformed request body: {}", ex.getMessage());
        return new ErrorResponse("Malformed request body", null);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(AccountNotFoundException ex) {
        log.warn("Account not found: {}", ex.getMessage());
        return new ErrorResponse(ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return new ErrorResponse("Internal server error", null);
    }
}
