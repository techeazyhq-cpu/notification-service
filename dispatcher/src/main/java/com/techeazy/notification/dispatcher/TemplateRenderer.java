package com.techeazy.notification.dispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {{variable}} substitution. Strict on purpose: a missing variable fails the message
 * (permanently) instead of sending "Hello , your code is ".
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.\\-]+)\\s*}}");

    public static class MissingVariableException extends RuntimeException {
        public MissingVariableException(List<String> names) {
            super("Missing template variable(s): " + String.join(", ", names));
        }
    }

    private TemplateRenderer() {}

    public static String render(String template, Map<String, String> variables) {
        if (template == null) return null;
        List<String> missing = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.get(m.group(1));
            if (value == null) {
                if (!missing.contains(m.group(1))) missing.add(m.group(1));
                m.appendReplacement(out, "");
            } else {
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
        }
        m.appendTail(out);
        if (!missing.isEmpty()) throw new MissingVariableException(missing);
        return out.toString();
    }
}
