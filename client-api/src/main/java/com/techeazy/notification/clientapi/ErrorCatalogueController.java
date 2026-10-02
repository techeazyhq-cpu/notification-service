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

import com.techeazy.notification.error.ErrorCategory;
import com.techeazy.notification.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * The error dictionary (ADR-031), served without an API key so a client or an operator can look up any code or error
 * id that a response or a failed message carries.
 */
@RestController
@RequestMapping(ErrorCatalogueController.PATH)
@Tag(name = "Errors", description = "The dictionary of error codes, causes and resolutions")
public class ErrorCatalogueController {

    static final String PATH = "/v1/errors";

    public record ErrorCodeView(String errorId, String code, ErrorCategory category, Integer httpStatus,
                                boolean retryable, String title, String cause, String resolution) {

        static ErrorCodeView of(ErrorCode errorCode) {
            Integer httpStatus = errorCode.httpStatus().isPresent() ? errorCode.httpStatus().getAsInt() : null;
            return new ErrorCodeView(errorCode.errorId(), errorCode.code(), errorCode.category(), httpStatus,
                    errorCode.retryable(), errorCode.title(), errorCode.cause(), errorCode.resolution());
        }
    }

    @GetMapping
    @Operation(summary = "List every error code")
    public List<ErrorCodeView> all() {
        return Arrays.stream(ErrorCode.values()).map(ErrorCodeView::of).toList();
    }

    @GetMapping("/{errorIdOrCode}")
    @Operation(summary = "Explain one error code, by its error id (NS-5001) or its code (RATE_LIMITED)")
    public ErrorCodeView one(@PathVariable String errorIdOrCode) {
        return ErrorCode.findByErrorId(errorIdOrCode).or(() -> ErrorCode.findByCode(errorIdOrCode))
                .map(ErrorCodeView::of)
                .orElseThrow(() -> ApiException.notFound("No error code " + errorIdOrCode));
    }
}
