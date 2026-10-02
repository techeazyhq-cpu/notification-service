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

/**
 * One entry of the error dictionary as both APIs serve it (ADR-031). {@code httpStatus} is null for a delivery
 * outcome, which is reported on the message rather than as a response.
 */
public record ErrorCodeView(String errorId, String code, ErrorCategory category, Integer httpStatus,
                            boolean retryable, String title, String cause, String resolution) {

    public static ErrorCodeView of(ErrorCode errorCode) {
        Integer httpStatus = errorCode.httpStatus().isPresent() ? errorCode.httpStatus().getAsInt() : null;
        return new ErrorCodeView(errorCode.errorId(), errorCode.code(), errorCode.category(), httpStatus,
                errorCode.retryable(), errorCode.title(), errorCode.cause(), errorCode.resolution());
    }
}
