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

package com.techeazy.notification.domain;

import org.junit.jupiter.api.Test;

import static com.techeazy.notification.domain.RequestStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class RequestStatusTest {

    @Test
    void processingWhileAnyMessageIsInFlight() {
        assertThat(derive(10, 3, 5, 2)).isEqualTo(PROCESSING);
    }

    @Test
    void processingWhenMessagesAreNotAccountedFor() {
        assertThat(derive(10, 0, 4, 1)).isEqualTo(PROCESSING);
    }

    @Test
    void completedWhenAllSent() {
        assertThat(derive(10, 0, 10, 0)).isEqualTo(COMPLETED);
    }

    @Test
    void failedWhenAllFailed() {
        assertThat(derive(3, 0, 0, 3)).isEqualTo(FAILED);
    }

    @Test
    void partiallyFailedWhenMixed() {
        assertThat(derive(10, 0, 7, 3)).isEqualTo(PARTIALLY_FAILED);
    }
}
