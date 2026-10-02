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

import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.SpendCapExceededException;
import com.techeazy.notification.error.ErrorBody;
import com.techeazy.notification.error.ErrorCategory;
import com.techeazy.notification.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final ApiExceptionHandler handler = new ApiExceptionHandler(() -> Optional.of(TRACE_ID));

    @Test
    void anApiErrorCarriesItsCatalogueEntryAndTheTraceId() {
        ResponseEntity<ErrorBody> response =
                handler.api(new ApiException(ErrorCode.CHANNEL_NOT_ALLOWED,
                        "Client is not allowed to use channel SMS"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(new ErrorBody("CHANNEL_NOT_ALLOWED",
                "Client is not allowed to use channel SMS", "NS-2008", ErrorCategory.ACCESS, false, TRACE_ID,
                "/v1/errors/NS-2008"));
    }

    @Test
    void aBillingRefusalIsReportedWithItsCatalogueEntry() {
        ResponseEntity<ErrorBody> response = handler.spendCap(
                new SpendCapExceededException(Money.of("100.00", "EUR"), Money.of("100.05", "EUR")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody()).extracting(ErrorBody::code, ErrorBody::errorId, ErrorBody::retryable)
                .containsExactly("SPEND_CAP_EXCEEDED", "NS-4002", false);
    }

    @Test
    void anUnknownPathIsNotFoundRatherThanAServerError() {
        ResponseEntity<ErrorBody> response =
                handler.unexpected(new NoResourceFoundException(HttpMethod.GET, "actuator/prometheus"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).extracting(ErrorBody::code, ErrorBody::errorId)
                .containsExactly("NOT_FOUND", "NS-3001");
    }

    @Test
    void anUnsupportedMethodKeepsItsOwnStatusAndCode() {
        ResponseEntity<ErrorBody> response = handler.unexpected(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).extracting(ErrorBody::code, ErrorBody::errorId)
                .containsExactly("METHOD_NOT_ALLOWED", "NS-1002");
    }

    @Test
    void anUnsupportedContentTypeHasItsOwnCode() {
        ResponseEntity<ErrorBody> response = handler.unexpected(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).extracting(ErrorBody::code).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void aGenuineFailureIsStillAnOpaqueServerErrorButNamesItsTrace() {
        ResponseEntity<ErrorBody> response =
                handler.unexpected(new IllegalStateException("connection refused to 10.0.0.5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(new ErrorBody("INTERNAL_ERROR", "Unexpected error", "NS-9001",
                ErrorCategory.PLATFORM, true, TRACE_ID, "/v1/errors/NS-9001"));
    }

    @Test
    void withoutATraceTheTraceIdIsLeftOut() {
        ApiExceptionHandler untraced = new ApiExceptionHandler(Optional::empty);

        ResponseEntity<ErrorBody> response = untraced.api(ApiException.notFound("Request not found"));

        assertThat(response.getBody()).extracting(ErrorBody::traceId).isNull();
    }
}
