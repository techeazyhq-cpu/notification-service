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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRulesTest {

    @Test
    void aShortGsmSmsIsOneSegment() {
        ChannelRules.Check check = ChannelRules.check(Channel.SMS, null, "Your code is {{code}}. Do not share it.",
                List.of("code"));

        assertThat(check.passed()).isTrue();
        assertThat(check.warnings()).isEmpty();
        assertThat(check.smsLength().encoding()).isEqualTo("GSM-7");
        assertThat(check.smsLength().segments()).isEqualTo(1);
    }

    @Test
    void placeholdersCountAtTheirAssumedLength() {
        assertThat(ChannelRules.measureSms("{{a}}").characters()).isEqualTo(ChannelRules.ASSUMED_VALUE_LENGTH);
    }

    @Test
    void gsmExtensionCharactersCountTwice() {
        assertThat(ChannelRules.measureSms("€").characters()).isEqualTo(2);
    }

    @Test
    void longGsmTextIsSplitIntoSegmentsOf153() {
        assertThat(ChannelRules.measureSms("a".repeat(160)).segments()).isEqualTo(1);
        assertThat(ChannelRules.measureSms("a".repeat(161)).segments()).isEqualTo(2);
        assertThat(ChannelRules.measureSms("a".repeat(306)).segments()).isEqualTo(2);
        assertThat(ChannelRules.measureSms("a".repeat(307)).segments()).isEqualTo(3);
    }

    @Test
    void oneNonGsmCharacterSwitchesTheWholeTextToUcs2() {
        ChannelRules.SmsLength length = ChannelRules.measureSms("Hello 😀 " + "a".repeat(70));

        assertThat(length.encoding()).isEqualTo("UCS-2");
        assertThat(length.segments()).isEqualTo(2);
    }

    @Test
    void anSmsOfMoreThanThreeSegmentsIsAViolation() {
        ChannelRules.Check check = ChannelRules.check(Channel.SMS, null, "a".repeat(460), List.of());

        assertThat(check.passed()).isFalse();
        assertThat(check.violations()).anyMatch(v -> v.contains("SMS segments"));
    }

    @Test
    void anSmsOfTwoSegmentsPassesWithAWarning() {
        ChannelRules.Check check = ChannelRules.check(Channel.SMS, null, "a".repeat(200), List.of());

        assertThat(check.passed()).isTrue();
        assertThat(check.warnings()).anyMatch(w -> w.contains("2 SMS segments"));
    }

    @Test
    void smsAndWhatsAppHaveNoSubjectAndNoMarkup() {
        assertThat(ChannelRules.check(Channel.SMS, "Hi", "Hello", List.of()).violations())
                .anyMatch(v -> v.contains("no subject"));
        assertThat(ChannelRules.check(Channel.WHATSAPP, null, "<b>Hello</b>", List.of()).violations())
                .anyMatch(v -> v.contains("plain text"));
    }

    @Test
    void anEmailNeedsAShortSingleLineSubject() {
        assertThat(ChannelRules.check(Channel.EMAIL, null, "<p>Hi</p>", List.of()).violations())
                .contains("An e-mail needs a subject");
        assertThat(ChannelRules.check(Channel.EMAIL, "x".repeat(151), "<p>Hi</p>", List.of()).violations())
                .anyMatch(v -> v.contains("longer than 150"));
        assertThat(ChannelRules.check(Channel.EMAIL, "a\nb", "<p>Hi</p>", List.of()).violations())
                .contains("The subject must be a single line");
    }

    @Test
    void anEmailBodyMayUseSimpleHtmlButNoActiveContent() {
        assertThat(ChannelRules.check(Channel.EMAIL, "Welcome", "<p>Hi <strong>{{name}}</strong></p>",
                List.of("name")).passed()).isTrue();
        assertThat(ChannelRules.check(Channel.EMAIL, "Welcome", "<p>Hi</p><script>x()</script>", List.of())
                .passed()).isFalse();
        assertThat(ChannelRules.check(Channel.EMAIL, "Welcome", "<p onclick=\"x()\">Hi</p>", List.of())
                .passed()).isFalse();
        assertThat(ChannelRules.check(Channel.EMAIL, "Welcome", "<a href=\"javascript:x()\">Hi</a>", List.of())
                .passed()).isFalse();
    }

    @Test
    void aWhatsAppBodyIsLimitedTo1024Characters() {
        assertThat(ChannelRules.check(Channel.WHATSAPP, null, "a".repeat(1024), List.of()).passed()).isTrue();
        assertThat(ChannelRules.check(Channel.WHATSAPP, null, "a".repeat(1025), List.of()).passed()).isFalse();
    }

    @Test
    void aWhatsAppBodyThatEndsWithAPlaceholderIsWarnedAbout() {
        ChannelRules.Check check = ChannelRules.check(Channel.WHATSAPP, null, "Your order is {{orderId}}",
                List.of("orderId"));

        assertThat(check.passed()).isTrue();
        assertThat(check.warnings()).anyMatch(w -> w.contains("placeholder"));
    }

    @Test
    void placeholdersThatWereNotAskedForAreAViolationAndUnusedOnesAWarning() {
        ChannelRules.Check check = ChannelRules.check(Channel.SMS, null, "Hi {{firstName}}, {{amount}} is due",
                List.of("firstName", "dueDate"));

        assertThat(check.violations()).contains("Uses placeholders that were not asked for: amount");
        assertThat(check.warnings()).contains("Does not use: dueDate");
    }

    @Test
    void theRecipientPlaceholderIsAlwaysAllowed() {
        assertThat(ChannelRules.check(Channel.SMS, null, "Sent to {{recipient}}", List.of()).passed()).isTrue();
    }

    @Test
    void malformedPlaceholdersAreAViolation() {
        assertThat(ChannelRules.check(Channel.SMS, null, "Hi {{name}", List.of("name")).violations())
                .anyMatch(v -> v.contains("malformed"));
    }

    @Test
    void pushAndEmptyBodiesAreRefused() {
        assertThat(ChannelRules.check(Channel.PUSH, null, "Hi", List.of()).passed()).isFalse();
        assertThat(ChannelRules.check(Channel.SMS, null, " ", List.of()).violations()).contains("The body is empty");
    }
}
