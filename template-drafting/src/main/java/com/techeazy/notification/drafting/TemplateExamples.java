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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.application.TemplateRenderer;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The library of example templates (ADR-046), read once from {@value #RESOURCE}. Every example must meet its
 * channel's rules without a warning, the same rules an AI draft is held to (ADR-041), and ids must be unique: a
 * catalogue that breaks either is refused when it is loaded, so a bad example fails the build and startup rather than
 * reaching an author. Using the library spends no tokens and works whether AI drafting is on or off.
 *
 * <p>Each language in {@link #TRANSLATIONS} has a file of its own, {@code template-examples-<language>.json}, that
 * translates English examples' subject and body by id. A translation keeps the English example's channel, category,
 * name and description, and must use exactly the same placeholders, so it cannot drop one. Its SMS text may leave
 * GSM-7 and so take more than one segment; those two warnings come with the script and are the only ones allowed.
 */
public final class TemplateExamples {

    static final String RESOURCE = "template-examples.json";
    static final String ENGLISH = "en";
    /** The languages the English examples are translated into, in the order a picker lists them. */
    static final List<String> TRANSLATIONS = List.of("hi", "ta", "ar");

    private record Entry(String id, Channel channel, MessageCategory category, String name, String description,
                         String subject, String body) {}

    private record Translation(String id, String subject, String body) {}

    private final List<TemplateExample> examples;

    private TemplateExamples(List<TemplateExample> examples) {
        this.examples = examples;
    }

    /** The catalogue shipped with the service, English first and then each translation. */
    public static TemplateExamples load() {
        try (InputStream english = resource(RESOURCE)) {
            Map<String, InputStream> translations = new LinkedHashMap<>();
            for (String language : TRANSLATIONS) {
                translations.put(language, resource("template-examples-" + language + ".json"));
            }
            try {
                return from(english, translations);
            } finally {
                for (InputStream translation : translations.values()) {
                    translation.close();
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static TemplateExamples from(InputStream catalogue) throws IOException {
        return from(catalogue, Map.of());
    }

    static TemplateExamples from(InputStream catalogue, Map<String, InputStream> translations) throws IOException {
        ObjectMapper json = new ObjectMapper();
        List<Entry> entries = json.readValue(catalogue, new TypeReference<>() {});
        Map<String, TemplateExample> english = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (english.putIfAbsent(entry.id(), checked(entry)) != null) {
                throw new IllegalStateException("Example template id " + entry.id() + " is used twice");
            }
        }
        List<TemplateExample> examples = new ArrayList<>(english.values());
        for (Map.Entry<String, InputStream> file : translations.entrySet()) {
            List<Translation> translated = json.readValue(file.getValue(), new TypeReference<>() {});
            Set<String> ids = new HashSet<>();
            for (Translation translation : translated) {
                if (!ids.add(translation.id())) {
                    throw new IllegalStateException("Example template id " + translation.id() + " is translated "
                            + "twice into " + file.getKey());
                }
                examples.add(translated(english.get(translation.id()), translation, file.getKey()));
            }
        }
        return new TemplateExamples(List.copyOf(examples));
    }

    /** The examples for a channel and category, in catalogue order; null matches every channel or category. */
    public List<TemplateExample> find(Channel channel, MessageCategory category) {
        return find(channel, category, null);
    }

    /**
     * The examples for a channel, category and language, in catalogue order; null matches every one.
     *
     * @param language a BCP 47 tag such as {@code en} or {@code hi}
     */
    public List<TemplateExample> find(Channel channel, MessageCategory category, String language) {
        return examples.stream()
                .filter(example -> channel == null || example.channel() == channel)
                .filter(example -> category == null || example.category() == category)
                .filter(example -> language == null || example.language().equals(language))
                .toList();
    }

    private static InputStream resource(String name) {
        InputStream stream = TemplateExamples.class.getResourceAsStream(name);
        if (stream == null) {
            throw new IllegalStateException("The example template catalogue " + name + " is missing");
        }
        return stream;
    }

    private static TemplateExample checked(Entry entry) {
        List<String> variables = List.copyOf(TemplateRenderer.requiredVariables(entry.subject(), entry.body()));
        ChannelRules.Check check = ChannelRules.check(entry.channel(), entry.subject(), entry.body(), variables);
        refuseProblems(entry.id(), entry.channel(), check, check.warnings());
        return new TemplateExample(entry.id(), ENGLISH, entry.channel(), entry.category(), entry.name(),
                entry.description(), entry.subject(), entry.body(), variables, check.smsLength());
    }

    private static TemplateExample translated(TemplateExample original, Translation translation, String language) {
        String id = translation.id() + "-" + language;
        if (original == null) {
            throw new IllegalStateException("Example template " + id + " translates " + translation.id()
                    + ", which is not an English example");
        }
        List<String> variables = List.copyOf(TemplateRenderer.requiredVariables(translation.subject(),
                translation.body()));
        if (!Set.copyOf(variables).equals(Set.copyOf(original.variables()))) {
            throw new IllegalStateException("Example template " + id + " uses " + variables + " but "
                    + original.id() + " uses " + original.variables());
        }
        ChannelRules.Check check = ChannelRules.check(original.channel(), translation.subject(), translation.body(),
                variables);
        List<String> unexpected = check.warnings().stream().filter(warning -> !comesWithTheScript(warning, check))
                .toList();
        refuseProblems(id, original.channel(), check, unexpected);
        return new TemplateExample(id, language, original.channel(), original.category(), original.name(),
                original.description(), translation.subject(), translation.body(), variables, check.smsLength());
    }

    private static boolean comesWithTheScript(String warning, ChannelRules.Check check) {
        return check.smsLength() != null && (warning.equals(ChannelRules.UCS2_WARNING)
                || warning.equals(ChannelRules.segmentsWarning(check.smsLength().segments())));
    }

    private static void refuseProblems(String id, Channel channel, ChannelRules.Check check, List<String> warnings) {
        if (!check.passed() || !warnings.isEmpty()) {
            List<String> problems = Stream.concat(check.violations().stream(), warnings.stream()).toList();
            throw new IllegalStateException("Example template " + id + " does not meet the " + channel + " rules: "
                    + String.join("; ", problems));
        }
    }
}
