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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The example templates authors can start from without a model (ADR-046). */
class TemplateExamplesTest {

    private final TemplateExamples examples = TemplateExamples.load();

    @Test
    void everyExampleMeetsItsChannelsRulesWithoutAWarning() {
        assertThat(examples.find(null, null)).isNotEmpty().allSatisfy(example -> {
            ChannelRules.Check check = ChannelRules.check(example.channel(), example.subject(), example.body(),
                    example.variables());
            assertThat(check.violations()).as(example.id()).isEmpty();
            assertThat(check.warnings()).as(example.id()).isEmpty();
        });
    }

    @Test
    void everyChannelHasAnExampleInEveryCategory() {
        for (Channel channel : ChannelRules.SUPPORTED) {
            for (MessageCategory category : MessageCategory.values()) {
                assertThat(examples.find(channel, category)).as(channel + " " + category).isNotEmpty();
            }
        }
    }

    @Test
    void idsAreUniqueReadableSlugs() {
        Set<String> seen = new HashSet<>();
        assertThat(examples.find(null, null)).allSatisfy(example -> {
            assertThat(example.id()).matches("[a-z0-9]+(-[a-z0-9]+)*");
            assertThat(seen.add(example.id())).as("duplicate " + example.id()).isTrue();
        });
    }

    @Test
    void anExampleListsItsVariablesInOrderAndAnSmsItsLength() {
        TemplateExample example = examples.find(Channel.SMS, MessageCategory.OTP).getFirst();

        assertThat(example.variables()).containsExactly("code", "minutes");
        assertThat(example.smsLength().segments()).isEqualTo(1);
        assertThat(examples.find(Channel.EMAIL, null)).allSatisfy(email -> assertThat(email.smsLength()).isNull());
    }

    @Test
    void examplesAreFilteredByChannelAndCategory() {
        assertThat(examples.find(Channel.PUSH, MessageCategory.PROMOTIONAL)).isNotEmpty().allSatisfy(example -> {
            assertThat(example.channel()).isEqualTo(Channel.PUSH);
            assertThat(example.category()).isEqualTo(MessageCategory.PROMOTIONAL);
        });
        assertThat(examples.find(Channel.WHATSAPP, null)).extracting(TemplateExample::category)
                .containsAll(List.of(MessageCategory.values()));
    }

    @Test
    void aCatalogueWithAnExampleThatBreaksTheRulesIsRefused() {
        String broken = """
                [{"id":"bad-sms","channel":"SMS","category":"TRANSACTIONAL","name":"Bad","description":"Breaks a rule",
                  "body":"Call us on 0800 123 4567"}]
                """;

        assertThatThrownBy(() -> TemplateExamples.from(utf8(broken)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad-sms")
                .hasMessageContaining("phone number");
    }

    @Test
    void aCatalogueWithTheSameIdTwiceIsRefused() {
        String twice = """
                [{"id":"same","channel":"SMS","category":"OTP","name":"A","description":"A","body":"Code {{code}}."},
                 {"id":"same","channel":"SMS","category":"OTP","name":"B","description":"B","body":"Code {{code}}."}]
                """;

        assertThatThrownBy(() -> TemplateExamples.from(utf8(twice)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("same");
    }

    private static ByteArrayInputStream utf8(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
