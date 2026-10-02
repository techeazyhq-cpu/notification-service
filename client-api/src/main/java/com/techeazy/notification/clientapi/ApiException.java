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

import com.techeazy.notification.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** A refusal of the client API, named by its {@link ErrorCode}; the handler turns it into the error body (ADR-031). */
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
    public HttpStatus status() { return HttpStatus.valueOf(errorCode.httpStatus().orElseThrow()); }
    public String code() { return errorCode.code(); }

    public static ApiException badRequest(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    public static ApiException conflict(ErrorCode errorCode, String message) {
        return new ApiException(errorCode, message);
    }

    public static ApiException forbidden(ErrorCode errorCode, String message) {
        return new ApiException(errorCode, message);
    }
}
