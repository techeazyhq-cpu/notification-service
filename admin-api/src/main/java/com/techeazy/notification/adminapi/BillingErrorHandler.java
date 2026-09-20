package com.techeazy.notification.adminapi;

import com.techeazy.notification.billing.domain.AccountSuspendedException;
import com.techeazy.notification.billing.domain.BillingException;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.InvoiceAlreadyExistsException;
import com.techeazy.notification.billing.domain.SpendCapExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns billing rule violations into HTTP responses with a stable code and a readable message. */
@RestControllerAdvice
class BillingErrorHandler {

    record ErrorBody(String code, String message) {}

    @ExceptionHandler(BillingNotFoundException.class)
    ResponseEntity<ErrorBody> notFound(BillingNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", e);
    }

    @ExceptionHandler({InvalidBillingStateException.class, InvoiceAlreadyExistsException.class})
    ResponseEntity<ErrorBody> conflict(BillingException e) {
        return respond(HttpStatus.CONFLICT, "INVALID_STATE", e);
    }

    @ExceptionHandler(InvalidBillingDataException.class)
    ResponseEntity<ErrorBody> invalid(InvalidBillingDataException e) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e);
    }

    @ExceptionHandler(InsufficientCreditException.class)
    ResponseEntity<ErrorBody> insufficientCredit(InsufficientCreditException e) {
        return respond(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDIT", e);
    }

    @ExceptionHandler(SpendCapExceededException.class)
    ResponseEntity<ErrorBody> spendCapExceeded(SpendCapExceededException e) {
        return respond(HttpStatus.PAYMENT_REQUIRED, "SPEND_CAP_EXCEEDED", e);
    }

    @ExceptionHandler(AccountSuspendedException.class)
    ResponseEntity<ErrorBody> suspended(AccountSuspendedException e) {
        return respond(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", e);
    }

    private static ResponseEntity<ErrorBody> respond(HttpStatus status, String code, BillingException e) {
        return ResponseEntity.status(status).body(new ErrorBody(code, e.getMessage()));
    }
}
