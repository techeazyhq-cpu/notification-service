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
    void anEmailLinkMustPointToAPlaceholder() {
        assertThat(ChannelRules.check(Channel.EMAIL, "Shipped", "<p><a href=\"{{trackingUrl}}\">Track it</a></p>",
                List.of("trackingUrl")).passed()).isTrue();
        assertThat(ChannelRules.check(Channel.EMAIL, "Shipped", "<p><a href='{{ trackingUrl }}'>Track it</a></p>",
                List.of("trackingUrl")).passed()).isTrue();

        assertThat(ChannelRules.check(Channel.EMAIL, "Shipped", "<p><a href='email: ''>Contact us</a></p>",
                List.of()).violations()).anyMatch(v -> v.contains("must point to a placeholder"));
        assertThat(ChannelRules.check(Channel.EMAIL, "Shipped", "<a href=\"https://shop.example.com/track\">Track</a>",
                List.of()).violations()).anyMatch(v -> v.contains("must point to a placeholder"));
        assertThat(ChannelRules.check(Channel.EMAIL, "Shipped", "<a href=\"{{url}}/track\">Track</a>",
                List.of("url")).violations()).anyMatch(v -> v.contains("must point to a placeholder"));
    }

    @Test
    void inventedEmailAddressesAreAViolationOnEveryChannel() {
        String body = "Questions? Write to support@example.com and we will help.";

        for (Channel channel : List.of(Channel.SMS, Channel.WHATSAPP)) {
            assertThat(ChannelRules.check(channel, null, body, List.of()).violations())
                    .anyMatch(v -> v.contains("e-mail address") && v.contains("support@example.com"));
        }
        assertThat(ChannelRules.check(Channel.EMAIL, "Shipped", "<p>" + body + "</p>", List.of()).violations())
                .anyMatch(v -> v.contains("support@example.com"));
    }

    @Test
    void inventedUrlsAreAViolation() {
        assertThat(ChannelRules.check(Channel.SMS, null, "Track it at https://track.example.com/123 today",
                List.of()).violations()).anyMatch(v -> v.contains("link") && v.contains("https://track.example.com"));
        assertThat(ChannelRules.check(Channel.WHATSAPP, null, "See www.example.com for details", List.of())
                .violations()).anyMatch(v -> v.contains("www.example.com"));
    }

    @Test
    void inventedPhoneNumbersAndOtherLongNumbersAreAViolation() {
        assertThat(ChannelRules.check(Channel.SMS, null, "Call us on +1 (555) 010-2030 if it is late.", List.of())
                .violations()).anyMatch(v -> v.contains("number") && v.contains("+1 (555) 010-2030"));
        assertThat(ChannelRules.check(Channel.SMS, null, "Your order 1234567 has shipped.", List.of())
                .violations()).anyMatch(v -> v.contains("1234567"));
    }

    @Test
    void shortNumbersRangesAndAmountsAreNotPhoneNumbers() {
        assertThat(ChannelRules.check(Channel.SMS, null,
                "Your order {{orderId}} arrives in 2-3 days. You earned 1,000 points, valid for 30 days.",
                List.of("orderId")).passed()).isTrue();
    }

    @Test
    void bracketedStandInsAreAViolation() {
        ChannelRules.Check check = ChannelRules.check(Channel.EMAIL, "Shipped",
                "Dear {{firstName}},<br>Your order has shipped.<br>Best regards,<br>[Your Company Name]",
                List.of("firstName"));

        assertThat(check.violations()).anyMatch(v -> v.contains("stand-in") && v.contains("[Your Company Name]"));
        assertThat(ChannelRules.check(Channel.SMS, null, "Hi [Customer], your order has shipped.", List.of())
                .passed()).isFalse();
    }

    @Test
    void theSubjectIsCheckedForInventedDetailsToo() {
        assertThat(ChannelRules.check(Channel.EMAIL, "Order [ORDER-ID] has shipped", "<p>On its way.</p>", List.of())
                .violations()).anyMatch(v -> v.contains("[ORDER-ID]"));
    }

    @Test
    void theDraftsTheLocalModelWroteAreJudgedAsAReviewerWould() {
        assertThat(ChannelRules.check(Channel.EMAIL, "Order Shipment Notification",
                "Dear {{firstName}},<br>Your order has shipped and will arrive in 2-3 business days. If you have any "
                        + "questions, please contact us at <a href='email: ''>support@example.com</a>.<br>Best "
                        + "regards, <strong>Order Fulfillment Team</strong>", List.of("firstName", "orderId"))
                .passed()).isFalse();
        assertThat(ChannelRules.check(Channel.EMAIL, "Shipment Notification",
                "<p>Your order {{orderId}} has shipped and will arrive in 2-3 business days.</p>",
                List.of("firstName", "orderId")).passed()).isTrue();
    }

    @Test
    void emptyBodiesAreRefused() {
        assertThat(ChannelRules.check(Channel.SMS, null, " ", List.of()).violations()).contains("The body is empty");
        assertThat(ChannelRules.check(Channel.PUSH, "Shipped", " ", List.of()).violations())
                .contains("The body is empty");
    }

    @Test
    void everyChannelCanBeDrafted() {
        assertThat(ChannelRules.SUPPORTED).containsExactlyInAnyOrder(Channel.values());
    }

    @Test
    void aPushWithAShortTitleAndShortPlainTextPasses() {
        ChannelRules.Check check = ChannelRules.check(Channel.PUSH, "Your order has shipped",
                "Order {{orderId}} is on its way and arrives in 2-3 days.", List.of("orderId"));

        assertThat(check.passed()).isTrue();
        assertThat(check.warnings()).isEmpty();
        assertThat(check.smsLength()).isNull();
    }

    @Test
    void aPushNeedsATitleOfAtMost65CharactersOnOneLine() {
        assertThat(ChannelRules.check(Channel.PUSH, null, "On its way.", List.of()).violations())
                .anyMatch(v -> v.contains("A push notification needs a title"));
        assertThat(ChannelRules.check(Channel.PUSH, " ", "On its way.", List.of()).violations())
                .anyMatch(v -> v.contains("needs a title"));
        assertThat(ChannelRules.check(Channel.PUSH, "t".repeat(65), "On its way.", List.of()).passed()).isTrue();
        assertThat(ChannelRules.check(Channel.PUSH, "t".repeat(66), "On its way.", List.of()).violations())
                .anyMatch(v -> v.contains("longer than 65 characters"));
        assertThat(ChannelRules.check(Channel.PUSH, "Order\nshipped", "On its way.", List.of()).violations())
                .anyMatch(v -> v.contains("single line"));
    }

    @Test
    void aPushBodyIsPlainTextOfAtMost240CharactersWithAWarningOver120() {
        assertThat(ChannelRules.check(Channel.PUSH, "Shipped", "b".repeat(120), List.of()).warnings()).isEmpty();
        assertThat(ChannelRules.check(Channel.PUSH, "Shipped", "b".repeat(121), List.of()).warnings())
                .anyMatch(w -> w.contains("cut off"));
        assertThat(ChannelRules.check(Channel.PUSH, "Shipped", "b".repeat(240), List.of()).passed()).isTrue();
        assertThat(ChannelRules.check(Channel.PUSH, "Shipped", "b".repeat(241), List.of()).violations())
                .anyMatch(v -> v.contains("longer than 240 characters"));
        assertThat(ChannelRules.check(Channel.PUSH, "Shipped", "<b>On its way</b>", List.of()).violations())
                .anyMatch(v -> v.contains("Push messages are plain text"));
        assertThat(ChannelRules.check(Channel.PUSH, "<b>Shipped</b>", "On its way", List.of()).violations())
                .anyMatch(v -> v.contains("The title must be plain text"));
    }

    @Test
    void aPushIsHeldToTheSameInventedDetailRules() {
        assertThat(ChannelRules.check(Channel.PUSH, "Shipped", "Track it at www.example.com", List.of())
                .violations()).anyMatch(v -> v.contains("www.example.com"));
        assertThat(ChannelRules.check(Channel.PUSH, "[Brand] update", "On its way", List.of()).violations())
                .anyMatch(v -> v.contains("[Brand]"));
    }
}
