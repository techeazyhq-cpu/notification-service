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

import java.util.regex.Pattern;

/** Cheap syntactic checks so obviously bad recipients are rejected at ingest, not after retries. */
final class RecipientValidator {
    // Possessive quantifiers and dot-free domain labels: no backtracking, so hostile input cannot cause ReDoS.
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]++@[^@\\s.]++(?:\\.[^@\\s.]++)++$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    private RecipientValidator() {}

    /** @return null when valid, otherwise a human-readable reason */
    static String check(Channel channel, String recipient) {
        if (recipient == null || recipient.isBlank()) return "recipient is empty";
        return switch (channel) {
            case EMAIL -> EMAIL.matcher(recipient).matches() ? null : "not a valid email address";
            case SMS, WHATSAPP -> E164.matcher(recipient).matches() ? null : "not a valid E.164 phone number (e.g. +14155550123)";
            case PUSH -> recipient.length() <= 320 ? null : "device token too long";
        };
    }
}
