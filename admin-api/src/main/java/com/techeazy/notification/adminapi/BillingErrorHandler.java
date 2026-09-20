/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

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
