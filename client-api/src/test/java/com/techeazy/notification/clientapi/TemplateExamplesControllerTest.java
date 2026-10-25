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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.drafting.TemplateExample;
import com.techeazy.notification.drafting.TemplateExamples;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A client sees example templates only for the channels it may use (ADR-046). */
class TemplateExamplesControllerTest {

    private static final AuthenticatedClient ACME = new AuthenticatedClient(UUID.randomUUID(), "acme",
            Set.of(Channel.SMS, Channel.EMAIL));

    private final TemplateExamplesController controller = new TemplateExamplesController(TemplateExamples.load());

    @Test
    void withoutAFilterTheExamplesForEveryChannelTheClientMayUseAreListed() {
        assertThat(controller.examples(ACME, null, null)).isNotEmpty().extracting(TemplateExample::channel)
                .containsOnly(Channel.SMS, Channel.EMAIL);
    }

    @Test
    void examplesAreFilteredByChannelAndCategory() {
        assertThat(controller.examples(ACME, Channel.EMAIL, MessageCategory.OTP)).isNotEmpty()
                .allSatisfy(example -> {
                    assertThat(example.channel()).isEqualTo(Channel.EMAIL);
                    assertThat(example.category()).isEqualTo(MessageCategory.OTP);
                });
    }

    @Test
    void aChannelTheClientMayNotUseHasNoExamples() {
        assertThat(controller.examples(ACME, Channel.WHATSAPP, null)).isEmpty();
    }
}
