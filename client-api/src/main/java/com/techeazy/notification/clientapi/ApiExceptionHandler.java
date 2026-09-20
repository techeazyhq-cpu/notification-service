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

package com.techeazy.notification.clientapi;

import com.techeazy.notification.billing.domain.AccountSuspendedException;
import com.techeazy.notification.billing.domain.BillingException;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.SpendCapExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorBody(String code, String message) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorBody> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage()));
    }

    @ExceptionHandler(InsufficientCreditException.class)
    ResponseEntity<ErrorBody> insufficientCredit(InsufficientCreditException e) {
        return billing(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDIT", e);
    }

    @ExceptionHandler(SpendCapExceededException.class)
    ResponseEntity<ErrorBody> spendCap(SpendCapExceededException e) {
        return billing(HttpStatus.PAYMENT_REQUIRED, "SPEND_CAP_EXCEEDED", e);
    }

    @ExceptionHandler(AccountSuspendedException.class)
    ResponseEntity<ErrorBody> suspended(AccountSuspendedException e) {
        return billing(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED", e);
    }

    @ExceptionHandler(BillingNotFoundException.class)
    ResponseEntity<ErrorBody> billingNotFound(BillingNotFoundException e) {
        return billing(HttpStatus.NOT_FOUND, "NOT_FOUND", e);
    }

    @ExceptionHandler(InvalidBillingStateException.class)
    ResponseEntity<ErrorBody> billingConflict(InvalidBillingStateException e) {
        return billing(HttpStatus.CONFLICT, "INVALID_STATE", e);
    }

    @ExceptionHandler(InvalidBillingDataException.class)
    ResponseEntity<ErrorBody> billingInvalid(InvalidBillingDataException e) {
        return billing(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e);
    }

    private static ResponseEntity<ErrorBody> billing(HttpStatus status, String code, BillingException e) {
        return ResponseEntity.status(status).body(new ErrorBody(code, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", msg));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> unreadable(Exception e) {
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", "Malformed request: " + e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorBody> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorBody("PAYLOAD_TOO_LARGE", "Upload too large"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.internalServerError().body(new ErrorBody("INTERNAL_ERROR", "Unexpected error"));
    }
}
