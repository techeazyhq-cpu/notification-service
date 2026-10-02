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
package com.techeazy.notification.adminapi.auth;

import com.techeazy.notification.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** A refused authentication or account operation, named by its {@link ErrorCode} (ADR-031). */
public class AuthException extends RuntimeException {

    private final transient ErrorCode errorCode;

    private AuthException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static AuthException of(ErrorCode errorCode, String message) {
        return new AuthException(errorCode, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode.code();
    }

    public HttpStatus status() {
        return HttpStatus.valueOf(errorCode.httpStatus().orElseThrow());
    }

    static AuthException invalidCredentials() {
        return of(ErrorCode.INVALID_CREDENTIALS, "Invalid user name, password or verification code");
    }

    static AuthException otpRequired() {
        return of(ErrorCode.OTP_REQUIRED, "A verification code is required");
    }

    static AuthException locked() {
        return of(ErrorCode.ACCOUNT_LOCKED, "Too many failed attempts; try again later");
    }

    static AuthException reauthenticationFailed() {
        return of(ErrorCode.REAUTHENTICATION_FAILED, "The current password or verification code is incorrect");
    }

    static AuthException invalid(String message) {
        return of(ErrorCode.INVALID_REQUEST, message);
    }

    static AuthException conflict(String message) {
        return of(ErrorCode.INVALID_STATE, message);
    }

    static AuthException notFound(String message) {
        return of(ErrorCode.NOT_FOUND, message);
    }
}
