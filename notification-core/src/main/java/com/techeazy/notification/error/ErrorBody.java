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
package com.techeazy.notification.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of every error response from the client and admin APIs (ADR-031). {@code code} and {@code message} are the
 * fields clients have always read; the rest come from the {@link ErrorCode} catalogue. {@code traceId} is left out
 * when the request has no trace.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorBody(String code, String message, String errorId, ErrorCategory category, boolean retryable,
                        String traceId, String docs) {

    public static ErrorBody of(ErrorCode errorCode, String message, String traceId) {
        return new ErrorBody(errorCode.code(), message, errorCode.errorId(), errorCode.category(),
                errorCode.retryable(), traceId, documentationPath(errorCode));
    }

    /** Where the code's cause and resolution are served, relative to the client API. */
    public static String documentationPath(ErrorCode errorCode) {
        return "/v1/errors/" + errorCode.errorId();
    }
}
