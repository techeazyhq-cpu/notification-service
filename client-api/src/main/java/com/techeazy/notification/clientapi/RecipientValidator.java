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
