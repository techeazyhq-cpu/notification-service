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
package com.techeazy.notification.adminapi;

import com.techeazy.notification.adminapi.auth.AuthException;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.error.ErrorCodeView;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The admin API serves the same dictionary its error bodies link to (ADR-031). */
class ErrorCatalogueControllerTest {

    private final ErrorCatalogueController controller = new ErrorCatalogueController();

    @Test
    void listsEveryCodeInCatalogueOrder() {
        assertThat(controller.all()).extracting(ErrorCodeView::errorId)
                .containsExactly(Arrays.stream(ErrorCode.values()).map(ErrorCode::errorId).toArray(String[]::new));
    }

    @Test
    void explainsOneCodeByErrorIdOrSymbolicCode() {
        assertThat(controller.one("ns-2003")).isEqualTo(ErrorCodeView.of(ErrorCode.OTP_REQUIRED));
        assertThat(controller.one("OTP_REQUIRED")).isEqualTo(ErrorCodeView.of(ErrorCode.OTP_REQUIRED));
    }

    @Test
    void everyLinkAnAdminErrorCarriesResolvesHere() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            if (errorCode.httpStatus().isEmpty()) {
                continue;
            }
            String docs = new AdminErrorHandler(Optional::empty)
                    .authentication(AuthException.of(errorCode, "x")).getBody().docs();

            assertThat(docs).startsWith(ErrorCatalogueController.PATH + "/");
            assertThat(controller.one(docs.substring(docs.lastIndexOf('/') + 1)).code()).isEqualTo(errorCode.code());
        }
    }

    @Test
    void anUnknownCodeIsNotFound() {
        ResponseStatusException refused = catchThrowableOfType(ResponseStatusException.class,
                () -> controller.one("NS-0000"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
