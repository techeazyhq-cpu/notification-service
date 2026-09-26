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

import org.springframework.http.HttpStatus;

/** A refused authentication or account operation, carrying the stable code and HTTP status the API reports. */
public class AuthException extends RuntimeException {

    private final transient String code;
    private final transient HttpStatus status;

    AuthException(HttpStatus status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid user name, password or verification code");
    }

    static AuthException otpRequired() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "OTP_REQUIRED", "A verification code is required");
    }

    static AuthException locked() {
        return new AuthException(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_LOCKED", "Too many failed attempts; try again later");
    }

    static AuthException reauthenticationFailed() {
        return new AuthException(HttpStatus.FORBIDDEN, "REAUTHENTICATION_FAILED", "The current password or verification code is incorrect");
    }

    static AuthException invalid(String message) {
        return new AuthException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    static AuthException conflict(String message) {
        return new AuthException(HttpStatus.CONFLICT, "INVALID_STATE", message);
    }

    static AuthException notFound(String message) {
        return new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
