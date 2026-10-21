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

package com.techeazy.notification.drafting;

import com.techeazy.notification.error.ErrorCode;

import java.util.List;

/** Why a draft was not produced. Each API maps the {@link Reason} to its own error format. */
public class DraftingException extends RuntimeException {

    /** The reasons a draft can be refused or fail, each with the catalogue code both APIs answer with. */
    public enum Reason {
        /** No API key is configured, or drafting is switched off for clients. */
        DISABLED(ErrorCode.DRAFTING_NOT_AVAILABLE),
        /** The brief is incomplete or out of bounds. */
        INVALID_REQUEST(ErrorCode.INVALID_REQUEST),
        /** The caller asked for drafts faster than the per-minute limit. */
        RATE_LIMITED(ErrorCode.RATE_LIMITED),
        /** The monthly token budget, the platform's or the client's, is spent. */
        BUDGET_EXHAUSTED(ErrorCode.DRAFT_BUDGET_EXHAUSTED),
        /** The model declined the brief. */
        DECLINED(ErrorCode.DRAFT_DECLINED),
        /** Every attempt broke the channel's rules, so no draft is returned. */
        OUTPUT_REJECTED(ErrorCode.DRAFT_RULES_NOT_MET),
        /** The model could not be reached. */
        UNAVAILABLE(ErrorCode.DRAFTING_MODEL_UNAVAILABLE);

        private final ErrorCode errorCode;

        Reason(ErrorCode errorCode) {
            this.errorCode = errorCode;
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }

    private final Reason reason;
    private final transient List<String> violations;
    private final long retryAfterSeconds;

    public DraftingException(Reason reason, String message) {
        this(reason, message, List.of(), 0);
    }

    public DraftingException(Reason reason, String message, List<String> violations, long retryAfterSeconds) {
        super(message);
        this.reason = reason;
        this.violations = List.copyOf(violations);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Reason reason() {
        return reason;
    }

    /** For {@link Reason#OUTPUT_REJECTED}: the rules the last attempt broke. */
    public List<String> violations() {
        return violations;
    }

    /** The message for the error body, followed by the broken rules when there are any. */
    public String describe() {
        return violations.isEmpty() ? getMessage() : getMessage() + ": " + String.join("; ", violations);
    }

    /** For {@link Reason#RATE_LIMITED}: seconds until another draft is allowed; 0 when not applicable. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
