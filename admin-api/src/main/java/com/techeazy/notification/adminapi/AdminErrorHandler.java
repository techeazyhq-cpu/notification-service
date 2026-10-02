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

import com.techeazy.notification.adminapi.auth.AuthException;
import com.techeazy.notification.application.ProviderDestinationRefusedException;
import com.techeazy.notification.billing.domain.AccountSuspendedException;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.InvoiceAlreadyExistsException;
import com.techeazy.notification.billing.domain.SpendCapExceededException;
import com.techeazy.notification.error.ErrorBody;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.error.TraceIdSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * Answers every refusal and failure of the admin API with an {@link ErrorBody} from the catalogue (ADR-031), so the
 * admin console and scripts read the same shape as client integrations do.
 */
@RestControllerAdvice
public class AdminErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminErrorHandler.class);

    private final TraceIdSource traceIds;

    public AdminErrorHandler(TraceIdSource traceIds) {
        this.traceIds = traceIds;
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<ErrorBody> authentication(AuthException e) {
        return respond(e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> statusOnly(ResponseStatusException e) {
        if (e.getStatusCode().is5xxServerError()) {
            return unexpected(e);
        }
        ErrorCode errorCode = ErrorCode.forHttpRefusal(e.getStatusCode().value());
        return respond(errorCode, e.getReason() == null ? errorCode.title() : e.getReason());
    }

    @ExceptionHandler(ProviderDestinationRefusedException.class)
    ResponseEntity<ErrorBody> destinationRefused(ProviderDestinationRefusedException e) {
        return respond(ErrorCode.PROVIDER_DESTINATION_REFUSED, e.getMessage());
    }

    @ExceptionHandler(BillingNotFoundException.class)
    ResponseEntity<ErrorBody> billingNotFound(BillingNotFoundException e) {
        return respond(ErrorCode.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({InvalidBillingStateException.class, InvoiceAlreadyExistsException.class})
    ResponseEntity<ErrorBody> billingConflict(RuntimeException e) {
        return respond(ErrorCode.INVALID_STATE, e.getMessage());
    }

    @ExceptionHandler(InvalidBillingDataException.class)
    ResponseEntity<ErrorBody> billingInvalid(InvalidBillingDataException e) {
        return respond(ErrorCode.INVALID_REQUEST, e.getMessage());
    }

    @ExceptionHandler(InsufficientCreditException.class)
    ResponseEntity<ErrorBody> insufficientCredit(InsufficientCreditException e) {
        return respond(ErrorCode.INSUFFICIENT_CREDIT, e.getMessage());
    }

    @ExceptionHandler(SpendCapExceededException.class)
    ResponseEntity<ErrorBody> spendCapExceeded(SpendCapExceededException e) {
        return respond(ErrorCode.SPEND_CAP_EXCEEDED, e.getMessage());
    }

    @ExceptionHandler(AccountSuspendedException.class)
    ResponseEntity<ErrorBody> suspended(AccountSuspendedException e) {
        return respond(ErrorCode.ACCOUNT_SUSPENDED, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> validation(MethodArgumentNotValidException e) {
        String fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + " " + field.getDefaultMessage()).collect(Collectors.joining("; "));
        return respond(ErrorCode.INVALID_REQUEST, fieldErrors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorBody> unreadable(Exception e) {
        return respond(ErrorCode.INVALID_REQUEST, "Malformed request: " + e.getMessage());
    }

    /**
     * Rethrown so Spring Security answers them through its entry point and access-denied handler, which already write
     * the same error body; handling them here would bypass the account-setup explanation.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void security(RuntimeException e) {
        throw e;
    }

    /**
     * Spring's own refusals (unknown path, unsupported method or media type) keep their status and are not logged as
     * errors. Anything else is an opaque 500 whose trace id leads to the logged cause.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception e) {
        if (e instanceof ErrorResponse refusal && refusal.getStatusCode().is4xxClientError()) {
            return respond(ErrorCode.forHttpRefusal(refusal.getStatusCode().value()), refusal.getBody().getDetail());
        }
        log.error("Unhandled error", e);
        return respond(ErrorCode.INTERNAL_ERROR, "Unexpected error");
    }

    private ResponseEntity<ErrorBody> respond(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.httpStatus().orElseThrow())
                .body(ErrorBody.of(errorCode, message, traceIds.currentTraceId().orElse(null),
                        ErrorCatalogueController.PATH));
    }
}
