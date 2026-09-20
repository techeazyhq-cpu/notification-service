package com.techeazy.notification.clientapi;

import com.techeazy.notification.clientapi.Dtos.MessageView;

import java.util.List;

/** CSV layout for exported messages. */
final class MessageCsv {

    static final List<String> HEADER = List.of("recipient", "status", "attempts", "lastError", "providerMessageId", "sentAt");

    private MessageCsv() {}

    static List<String> row(MessageView m) {
        return List.of(
                m.recipient(), // the client's own data, exported verbatim (phone numbers start with '+')
                m.status().name(),
                Integer.toString(m.attempts()),
                neutralize(m.lastError()),
                neutralize(m.providerMessageId()),
                m.sentAt() == null ? "" : m.sentAt().toString());
    }

    /**
     * Free text can come from a provider's response. Spreadsheets treat cells starting with = + - @ (or a control
     * character) as formulas, so those get a leading apostrophe to keep them inert (OWASP CSV injection guidance).
     */
    static String neutralize(String text) {
        if (text == null || text.isEmpty()) return "";
        char first = text.charAt(0);
        boolean risky = first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
        return risky ? "'" + text : text;
    }
}
