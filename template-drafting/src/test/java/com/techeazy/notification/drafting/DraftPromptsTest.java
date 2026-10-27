/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.drafting;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the model is told about the placeholders a brief lists (ADR-048). */
class DraftPromptsTest {

    private static final DraftRequest ORDER_SHIPPED = new DraftRequest(Channel.SMS, MessageCategory.TRANSACTIONAL,
            DraftTone.FRIENDLY, "Tell the customer their order has shipped", List.of("firstName", "orderId"));

    @Test
    void theSystemPromptAsksForEveryListedPlaceholderAndNoOther() {
        assertThat(DraftPrompts.SYSTEM)
                .contains("Use every placeholder the brief lists, each at least once")
                .contains("Use no placeholder the brief does not list");
    }

    @Test
    void theBriefNamesThePlaceholdersToUseEachAtLeastOnce() {
        assertThat(DraftPrompts.brief(ORDER_SHIPPED))
                .contains("Placeholders to use, each at least once: {{firstName}}, {{orderId}}");
    }

    @Test
    void aBriefWithoutPlaceholdersSaysSo() {
        DraftRequest none = new DraftRequest(Channel.SMS, MessageCategory.TRANSACTIONAL, DraftTone.FRIENDLY,
                "Tell the customer the shop is closed tomorrow", List.of());

        assertThat(DraftPrompts.brief(none)).contains("Placeholders to use: none besides {{recipient}}, if needed");
    }

    @Test
    void theCorrectionNamesListedPlaceholdersThePreviousDraftLeftOut() {
        String correction = DraftPrompts.correction(ORDER_SHIPPED, null, "Hi {{firstName}}, it shipped!",
                List.of("Contains a phone number"));

        assertThat(correction).contains("- Contains a phone number")
                .contains("- Does not use {{orderId}}, which the brief lists");
    }
}
