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
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.error.ErrorBody;
import com.techeazy.notification.error.ErrorCategory;
import com.techeazy.notification.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Every admin API refusal answers with the catalogue's error body, as the client API does (ADR-031). */
class AdminErrorHandlerTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    private final AdminErrorHandler handler = new AdminErrorHandler(() -> Optional.of(TRACE_ID));

    @Test
    void anAuthenticationRefusalKeepsItsCodeAndStatus() {
        ResponseEntity<ErrorBody> response = handler.authentication(AuthException.of(ErrorCode.OTP_REQUIRED,
                "A verification code is required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(new ErrorBody("OTP_REQUIRED", "A verification code is required",
                "NS-2003", ErrorCategory.ACCESS, false, TRACE_ID, "/v1/errors/NS-2003"));
    }

    @Test
    void aStatusOnlyRefusalGetsTheCodeForItsStatusAndKeepsItsReason() {
        ResponseEntity<ErrorBody> response = handler.statusOnly(
                new ResponseStatusException(HttpStatus.CONFLICT, "A policy for this scope already exists"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).extracting(ErrorBody::code, ErrorBody::message)
                .containsExactly("INVALID_STATE", "A policy for this scope already exists");
    }

    @Test
    void aStatusOnlyRefusalWithoutAReasonFallsBackToTheCodeTitle() {
        ResponseEntity<ErrorBody> response = handler.statusOnly(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(response.getBody()).extracting(ErrorBody::code, ErrorBody::message)
                .containsExactly("NOT_FOUND", ErrorCode.NOT_FOUND.title());
    }

    @Test
    void aRefusedProviderDestinationIsReportedAsSuch() {
        ResponseEntity<ErrorBody> response = handler.destinationRefused(
                new ProviderDestinationRefusedException("10.0.0.5 is a private address"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).extracting(ErrorBody::code).isEqualTo("PROVIDER_DESTINATION_REFUSED");
    }

    @Test
    void aBillingRefusalIsReportedWithItsCatalogueEntry() {
        ResponseEntity<ErrorBody> response = handler.billingNotFound(new BillingNotFoundException("No such plan"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).extracting(ErrorBody::code).isEqualTo("NOT_FOUND");
    }

    @Test
    void aGenuineFailureIsAnOpaqueServerErrorThatNamesItsTrace() {
        ResponseEntity<ErrorBody> response = handler.unexpected(new IllegalStateException("pool exhausted"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).extracting(ErrorBody::code, ErrorBody::message, ErrorBody::traceId)
                .containsExactly("INTERNAL_ERROR", "Unexpected error", TRACE_ID);
    }
}
