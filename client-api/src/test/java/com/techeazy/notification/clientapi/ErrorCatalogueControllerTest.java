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
import com.techeazy.notification.error.ErrorCodeView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ErrorCatalogueControllerTest {

    private final ErrorCatalogueController controller = new ErrorCatalogueController();

    @Test
    void listsEveryCodeInCatalogueOrder() {
        assertThat(controller.all()).extracting(ErrorCodeView::errorId)
                .containsExactly(java.util.Arrays.stream(ErrorCode.values()).map(ErrorCode::errorId)
                        .toArray(String[]::new));
    }

    @Test
    void explainsOneCodeByItsErrorId() {
        ErrorCodeView view = controller.one("ns-5001");

        assertThat(view).isEqualTo(new ErrorCodeView("NS-5001", "RATE_LIMITED", ErrorCategory.CAPACITY, 429, true,
                ErrorCode.RATE_LIMITED.title(), ErrorCode.RATE_LIMITED.cause(), ErrorCode.RATE_LIMITED.resolution()));
    }

    @Test
    void explainsOneCodeByItsSymbolicCodeToo() {
        assertThat(controller.one("SPEND_CAP_EXCEEDED").errorId()).isEqualTo("NS-4002");
    }

    @Test
    void aDeliveryOutcomeHasNoHttpStatus() {
        assertThat(controller.one("NS-6001").httpStatus()).isNull();
    }

    @Test
    void anUnknownCodeIsNotFound() {
        ApiException refused = catchThrowableOfType(ApiException.class, () -> controller.one("NS-0000"));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }
}
