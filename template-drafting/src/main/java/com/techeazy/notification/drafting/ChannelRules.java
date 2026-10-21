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

import com.techeazy.notification.application.TemplateRenderer;
import com.techeazy.notification.domain.Channel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules a template must meet on each channel before a draft is shown to its author (ADR-041). A violation makes
 * the draft unusable; a warning is shown next to it and left to the author's judgement.
 *
 * <p>SMS length is estimated with every placeholder standing in for {@value #ASSUMED_VALUE_LENGTH} characters, since
 * the real values are only known at send time: GSM-7 text fits 160 characters in one segment and 153 in each part of
 * a longer message; any other character switches the whole message to UCS-2, which fits 70 and 67.
 */
public final class ChannelRules {

    /** The channels a template can be drafted for; push notifications carry a title and data, not a template text. */
    public static final Set<Channel> SUPPORTED = EnumSet.of(Channel.SMS, Channel.EMAIL, Channel.WHATSAPP);
    public static final int SMS_MAX_SEGMENTS = 3;
    public static final int EMAIL_SUBJECT_MAX_LENGTH = 150;
    public static final int EMAIL_BODY_MAX_LENGTH = 10_000;
    /** The body limit of a WhatsApp Business message template, so a draft can also be submitted for approval. */
    public static final int WHATSAPP_BODY_MAX_LENGTH = 1024;
    static final int ASSUMED_VALUE_LENGTH = 12;

    private static final String GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXTENDED = "^{}\\[~]|€\f";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*[\\w.\\-]+\\s*}}");
    private static final Pattern HTML_TAG = Pattern.compile("<\\s*/?\\s*[a-zA-Z][^>]*>");
    private static final Pattern UNSAFE_HTML = Pattern.compile(
            "(?i)<\\s*(script|style|iframe|object|embed|form|input|link|meta|base)\\b|\\son[a-z]+\\s*=|javascript:");
    private static final Pattern STARTS_WITH_PLACEHOLDER = Pattern.compile("^\\s*\\{\\{");
    private static final Pattern ENDS_WITH_PLACEHOLDER = Pattern.compile("}}[\\s.!?]*$");

    /**
     * How long an SMS is.
     *
     * @param encoding   {@code GSM-7} or {@code UCS-2}
     * @param characters the length in that encoding's units, with placeholders at their assumed length
     * @param segments   how many SMS the text is sent and billed as
     */
    public record SmsLength(String encoding, int characters, int segments) {}

    /** The outcome of checking a template; {@code smsLength} is present for SMS only. */
    public record Check(List<String> violations, List<String> warnings, SmsLength smsLength) {

        public boolean passed() {
            return violations.isEmpty();
        }
    }

    private ChannelRules() {}

    /**
     * Checks a template against its channel's rules.
     *
     * @param allowedVariables the placeholders the author asked for; {@code {{recipient}}} is always allowed
     */
    public static Check check(Channel channel, String subject, String body, Collection<String> allowedVariables) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!SUPPORTED.contains(channel)) {
            violations.add("Templates cannot be drafted for " + channel);
            return new Check(violations, warnings, null);
        }
        if (body == null || body.isBlank()) {
            violations.add("The body is empty");
            return new Check(violations, warnings, null);
        }
        checkPlaceholders(subject, body, allowedVariables, violations, warnings);
        SmsLength smsLength = null;
        switch (channel) {
            case SMS -> smsLength = checkSms(subject, body, violations, warnings);
            case EMAIL -> checkEmail(subject, body, violations);
            case WHATSAPP -> checkWhatsApp(subject, body, violations, warnings);
            default -> throw new IllegalStateException("Unsupported channel " + channel);
        }
        return new Check(List.copyOf(violations), List.copyOf(warnings), smsLength);
    }

    /** Measures an SMS text as written; placeholders count at their assumed length. */
    public static SmsLength measureSms(String body) {
        String text = PLACEHOLDER.matcher(body).replaceAll(Matcher.quoteReplacement("0".repeat(ASSUMED_VALUE_LENGTH)));
        boolean gsm = text.codePoints().allMatch(c -> GSM_BASIC.indexOf(c) >= 0 || GSM_EXTENDED.indexOf(c) >= 0);
        if (gsm) {
            int septets = text.codePoints().map(c -> GSM_EXTENDED.indexOf(c) >= 0 ? 2 : 1).sum();
            return new SmsLength("GSM-7", septets, septets <= 160 ? 1 : (septets + 152) / 153);
        }
        int units = text.length();
        return new SmsLength("UCS-2", units, units <= 70 ? 1 : (units + 66) / 67);
    }

    private static void checkPlaceholders(String subject, String body, Collection<String> allowedVariables,
                                          List<String> violations, List<String> warnings) {
        if (TemplateRenderer.hasStrayBraces(subject) || TemplateRenderer.hasStrayBraces(body)) {
            violations.add("A placeholder is malformed: use {{name}} with matching braces");
        }
        Set<String> used = TemplateRenderer.variables(subject, body);
        Set<String> unknown = new LinkedHashSet<>(used);
        unknown.removeAll(allowedVariables);
        unknown.remove(TemplateRenderer.RECIPIENT);
        if (!unknown.isEmpty()) {
            violations.add("Uses placeholders that were not asked for: " + String.join(", ", unknown));
        }
        List<String> unused = allowedVariables.stream().filter(v -> !used.contains(v)).toList();
        if (!unused.isEmpty()) {
            warnings.add("Does not use: " + String.join(", ", unused));
        }
    }

    private static SmsLength checkSms(String subject, String body, List<String> violations, List<String> warnings) {
        noSubject(subject, "SMS", violations);
        noMarkup(body, "SMS", violations);
        SmsLength length = measureSms(body);
        if (length.segments() > SMS_MAX_SEGMENTS) {
            violations.add("About " + length.segments() + " SMS segments long; at most " + SMS_MAX_SEGMENTS
                    + " are allowed");
        } else if (length.segments() > 1) {
            warnings.add("About " + length.segments() + " SMS segments long; each segment is billed as one SMS");
        }
        if ("UCS-2".equals(length.encoding())) {
            warnings.add("Uses characters outside GSM-7, so a segment holds 70 characters instead of 160");
        }
        return length;
    }

    private static void checkEmail(String subject, String body, List<String> violations) {
        if (subject == null || subject.isBlank()) {
            violations.add("An e-mail needs a subject");
        } else {
            if (subject.length() > EMAIL_SUBJECT_MAX_LENGTH) {
                violations.add("The subject is longer than " + EMAIL_SUBJECT_MAX_LENGTH + " characters");
            }
            if (subject.contains("\n") || subject.contains("\r")) {
                violations.add("The subject must be a single line");
            }
            if (HTML_TAG.matcher(subject).find()) {
                violations.add("The subject must be plain text");
            }
        }
        if (body.length() > EMAIL_BODY_MAX_LENGTH) {
            violations.add("The body is longer than " + EMAIL_BODY_MAX_LENGTH + " characters");
        }
        if (UNSAFE_HTML.matcher(body).find()) {
            violations.add("The body contains scripts, styles, forms, embedded content or event handlers");
        }
    }

    private static void checkWhatsApp(String subject, String body, List<String> violations, List<String> warnings) {
        noSubject(subject, "WhatsApp", violations);
        noMarkup(body, "WhatsApp", violations);
        if (body.length() > WHATSAPP_BODY_MAX_LENGTH) {
            violations.add("The body is longer than " + WHATSAPP_BODY_MAX_LENGTH + " characters");
        }
        if (STARTS_WITH_PLACEHOLDER.matcher(body).find() || ENDS_WITH_PLACEHOLDER.matcher(body).find()) {
            warnings.add("Starts or ends with a placeholder, which WhatsApp does not approve in message templates");
        }
    }

    private static void noSubject(String subject, String channelName, List<String> violations) {
        if (subject != null && !subject.isBlank()) {
            violations.add(channelName + " messages have no subject");
        }
    }

    private static void noMarkup(String body, String channelName, List<String> violations) {
        if (HTML_TAG.matcher(body).find()) {
            violations.add(channelName + " messages are plain text: remove the HTML tags");
        }
    }
}
