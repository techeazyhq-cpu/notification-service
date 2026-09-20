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

package com.techeazy.notification.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal {{variable}} substitution: no expressions, no logic, so template authors cannot execute anything.
 * Strict on purpose: a missing variable fails (instead of sending "Hello , your code is ").
 */
public final class TemplateRenderer {

    /** Always available to every template; filled in from the message's recipient. */
    public static final String RECIPIENT = "recipient";

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

    /** For previews: fills in the variables it knows and leaves the other placeholders visible as written. */
    public static String renderLenient(String template, Map<String, String> variables) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Every variable the texts use, in order of first appearance, including {@link #RECIPIENT}. */
    public static Set<String> variables(String... texts) {
        Set<String> names = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) continue;
            Matcher m = PLACEHOLDER.matcher(text);
            while (m.find()) names.add(m.group(1));
        }
        return names;
    }

    /** The variables a sender must supply per recipient: {@link #variables} minus the built-in recipient. */
    public static Set<String> requiredVariables(String... texts) {
        Set<String> names = variables(texts);
        names.remove(RECIPIENT);
        return names;
    }

    /** True if the text has "{{" or "}}" that is not part of a well-formed placeholder (typo such as "{{ name }"). */
    public static boolean hasStrayBraces(String text) {
        if (text == null) return false;
        String rest = PLACEHOLDER.matcher(text).replaceAll("");
        return rest.contains("{{") || rest.contains("}}");
    }
}
