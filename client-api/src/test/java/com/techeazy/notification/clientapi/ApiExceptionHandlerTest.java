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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void anUnknownPathIsNotFoundRatherThanAServerError() {
        ResponseEntity<ApiExceptionHandler.ErrorBody> response =
                handler.unexpected(new NoResourceFoundException(HttpMethod.GET, "actuator/prometheus"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).extracting(ApiExceptionHandler.ErrorBody::code).isEqualTo("NOT_FOUND");
    }

    @Test
    void anUnsupportedMethodKeepsItsOwnStatus() {
        ResponseEntity<ApiExceptionHandler.ErrorBody> response =
                handler.unexpected(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).extracting(ApiExceptionHandler.ErrorBody::code).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void aGenuineFailureIsStillAnOpaqueServerError() {
        ResponseEntity<ApiExceptionHandler.ErrorBody> response =
                handler.unexpected(new IllegalStateException("connection refused to 10.0.0.5"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .isEqualTo(new ApiExceptionHandler.ErrorBody("INTERNAL_ERROR", "Unexpected error"));
    }
}
