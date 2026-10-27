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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The example templates authors can start from without a model (ADR-046). */
class TemplateExamplesTest {

    private final TemplateExamples examples = TemplateExamples.load();

    @Test
    void everyEnglishExampleMeetsItsChannelsRulesWithoutAWarning() {
        assertThat(examples.find(null, null, "en")).isNotEmpty().allSatisfy(example -> {
            ChannelRules.Check check = ChannelRules.check(example.channel(), example.subject(), example.body(),
                    example.variables());
            assertThat(check.violations()).as(example.id()).isEmpty();
            assertThat(check.warnings()).as(example.id()).isEmpty();
        });
    }

    @Test
    void aTranslationIsWarnedOnlyAboutWhatItsScriptDoesToAnSms() {
        assertThat(examples.find(null, null)).filteredOn(example -> !example.language().equals("en")).isNotEmpty()
                .allSatisfy(example -> {
                    ChannelRules.Check check = ChannelRules.check(example.channel(), example.subject(), example.body(),
                            example.variables());
                    assertThat(check.violations()).as(example.id()).isEmpty();
                    if (example.channel() == Channel.SMS) {
                        assertThat(check.warnings()).as(example.id()).contains(ChannelRules.UCS2_WARNING);
                        assertThat(example.smsLength().segments()).as(example.id())
                                .isLessThanOrEqualTo(example.category() == MessageCategory.OTP ? 1
                                        : ChannelRules.SMS_MAX_SEGMENTS);
                    } else {
                        assertThat(check.warnings()).as(example.id()).isEmpty();
                    }
                });
    }

    @Test
    void everyEnglishExampleIsTranslatedIntoEveryLanguageWithTheSamePlaceholders() {
        List<TemplateExample> english = examples.find(null, null, "en");
        for (String language : TemplateExamples.TRANSLATIONS) {
            assertThat(examples.find(null, null, language)).as(language).hasSameSizeAs(english);
            for (TemplateExample original : english) {
                TemplateExample translated = examples.find(original.channel(), original.category(), language).stream()
                        .filter(example -> example.id().equals(original.id() + "-" + language)).findFirst()
                        .orElseThrow(() -> new AssertionError(original.id() + " has no " + language + " translation"));
                assertThat(translated.variables()).as(translated.id())
                        .containsExactlyInAnyOrderElementsOf(original.variables());
                assertThat(translated.name()).isEqualTo(original.name());
                assertThat(translated.body()).isNotEqualTo(original.body());
            }
        }
    }

    @Test
    void everyChannelHasAnExampleInEveryCategoryInEveryLanguage() {
        for (String language : List.of("en", "hi", "ta", "ar")) {
            for (Channel channel : ChannelRules.SUPPORTED) {
                for (MessageCategory category : MessageCategory.values()) {
                    assertThat(examples.find(channel, category, language)).as(language + " " + channel + " "
                            + category).isNotEmpty();
                }
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
    void aTranslationThatDropsOrAddsAPlaceholderIsRefused() {
        String english = """
                [{"id":"otp","channel":"SMS","category":"OTP","name":"Code","description":"A code",
                  "body":"Your code is {{code}}. It expires in {{minutes}} minutes."}]
                """;
        String dropped = """
                [{"id":"otp","body":"आपका कोड {{code}} है।"}]
                """;

        assertThatThrownBy(() -> TemplateExamples.from(utf8(english), Map.of("hi", utf8(dropped))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("otp-hi")
                .hasMessageContaining("minutes");
    }

    @Test
    void aTranslationOfAnExampleThatDoesNotExistIsRefused() {
        String english = """
                [{"id":"otp","channel":"SMS","category":"OTP","name":"Code","description":"A code",
                  "body":"Your code is {{code}}."}]
                """;
        String unknown = """
                [{"id":"welcome","body":"مرحبًا {{firstName}}"}]
                """;

        assertThatThrownBy(() -> TemplateExamples.from(utf8(english), Map.of("ar", utf8(unknown))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("welcome-ar");
    }

    @Test
    void aTranslationIsHeldToEveryOtherRule() {
        String english = """
                [{"id":"help","channel":"SMS","category":"TRANSACTIONAL","name":"Help","description":"Help",
                  "body":"Hi {{firstName}}, reply here if you need help."}]
                """;
        String invented = """
                [{"id":"help","body":"வணக்கம் {{firstName}}, உதவிக்கு 0800 123 4567 ஐ அழைக்கவும்."}]
                """;

        assertThatThrownBy(() -> TemplateExamples.from(utf8(english), Map.of("ta", utf8(invented))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("help-ta")
                .hasMessageContaining("phone number");
    }

    @Test
    void examplesAreFilteredByLanguage() {
        assertThat(examples.find(Channel.SMS, MessageCategory.OTP, "ar")).isNotEmpty()
                .allSatisfy(example -> assertThat(example.language()).isEqualTo("ar"));
        assertThat(examples.find(Channel.SMS, MessageCategory.OTP))
                .extracting(TemplateExample::language).contains("en", "hi", "ta", "ar");
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
