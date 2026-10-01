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
package com.techeazy.notification.adminapi.audit;

import org.springframework.http.HttpStatus;

/** How an audited request ended, derived from its HTTP status so every endpoint is classified the same way. */
public enum AuditOutcome {
    /** The change was made. */
    SUCCEEDED,
    /** The caller was allowed to try, but the request was invalid or conflicted with the current state. */
    REJECTED,
    /** The caller was not signed in, or their role does not allow the change. */
    DENIED,
    /** The server failed while handling the request; the change may or may not have been made. */
    FAILED;

    public static AuditOutcome forStatus(int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            return DENIED;
        }
        if (statusCode >= HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return FAILED;
        }
        if (statusCode >= HttpStatus.BAD_REQUEST.value()) {
            return REJECTED;
        }
        return SUCCEEDED;
    }
}
