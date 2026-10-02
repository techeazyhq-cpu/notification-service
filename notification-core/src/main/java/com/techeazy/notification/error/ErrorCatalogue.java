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

import java.util.Arrays;

/** Renders the {@link ErrorCode} catalogue as the published Markdown dictionary, docs/error-codes.md. */
public final class ErrorCatalogue {

    private ErrorCatalogue() {
    }

    public static String markdown() {
        StringBuilder document = new StringBuilder();
        document.append("""
                # Error code dictionary

                Generated from `ErrorCode` in notification-core by `ErrorCatalogueDocumentTest`; do not edit by hand.
                The same catalogue is served without credentials at `GET /v1/errors` and `GET /v1/errors/{errorId}` on the
                client API, and at `GET /api/admin/errors` and `GET /api/admin/errors/{errorId}` on the admin API. The
                `docs` link in an error body points at the entry on the API that answered. See ADR-031.

                Every error response carries the same fields:

                ```json
                {
                  "code": "RATE_LIMITED",
                  "message": "API rate limit exceeded",
                  "errorId": "NS-5001",
                  "category": "CAPACITY",
                  "retryable": true,
                  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
                  "docs": "/v1/errors/NS-5001"
                }
                ```

                - **code** is stable and what programs branch on. **message** is for people and may change wording.
                - **errorId** is stable and what people quote to support; the digit after `NS-` is the category.
                - **retryable** says whether the same call, unchanged, can succeed later.
                - **traceId** finds the request's trace and every log line it produced (ADR-023).

                A failed message reports its delivery outcome the same way, in `errorCode` and `errorId` on the
                message (`GET /v1/messages/{id}`), next to the provider's own wording in `lastError`.

                ## Categories

                | Range | Category | Meaning |
                |---|---|---|
                """);
        for (ErrorCategory category : ErrorCategory.values()) {
            document.append("| NS-").append(category.rangeDigit()).append("xxx | ").append(category.label())
                    .append(" | ").append(category.meaning()).append(" |\n");
        }
        document.append("\n## Summary\n\n| Error id | Code | HTTP | Retryable | Title |\n|---|---|---|---|---|\n");
        for (ErrorCode code : ErrorCode.values()) {
            document.append("| [").append(code.errorId()).append("](#").append(anchor(code)).append(") | `")
                    .append(code.code()).append("` | ").append(httpStatusText(code)).append(" | ")
                    .append(code.retryable() ? "yes" : "no").append(" | ").append(code.title()).append(" |\n");
        }
        for (ErrorCategory category : ErrorCategory.values()) {
            appendCategory(document, category);
        }
        return document.toString();
    }

    private static void appendCategory(StringBuilder document, ErrorCategory category) {
        document.append("\n## ").append(category.label()).append(" (NS-").append(category.rangeDigit())
                .append("xxx)\n");
        Arrays.stream(ErrorCode.values()).filter(code -> code.category() == category).forEach(code -> document
                .append("\n### ").append(code.errorId()).append(' ').append(code.code()).append("\n\n")
                .append("**").append(code.title()).append("** · HTTP ").append(httpStatusText(code))
                .append(" · ").append(code.retryable() ? "retryable" : "not retryable").append("\n\n")
                .append("- **Cause:** ").append(code.cause()).append('\n')
                .append("- **Resolution:** ").append(code.resolution()).append('\n'));
    }

    private static String httpStatusText(ErrorCode code) {
        return code.httpStatus().isPresent() ? String.valueOf(code.httpStatus().getAsInt()) : "n/a (on the message)";
    }

    private static String anchor(ErrorCode code) {
        return (code.errorId() + "-" + code.code()).toLowerCase().replace('_', '-').replace(' ', '-');
    }
}
