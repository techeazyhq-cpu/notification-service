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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The library of example templates (ADR-046), read once from {@value #RESOURCE}. Every example must meet its
 * channel's rules without a warning, the same rules an AI draft is held to (ADR-041), and ids must be unique: a
 * catalogue that breaks either is refused when it is loaded, so a bad example fails the build and startup rather than
 * reaching an author. Using the library spends no tokens and works whether AI drafting is on or off.
 */
public final class TemplateExamples {

    static final String RESOURCE = "template-examples.json";

    private record Entry(String id, Channel channel, MessageCategory category, String name, String description,
                         String subject, String body) {}

    private final List<TemplateExample> examples;

    private TemplateExamples(List<TemplateExample> examples) {
        this.examples = examples;
    }

    /** The catalogue shipped with the service. */
    public static TemplateExamples load() {
        try (InputStream catalogue = TemplateExamples.class.getResourceAsStream(RESOURCE)) {
            if (catalogue == null) {
                throw new IllegalStateException("The example template catalogue " + RESOURCE + " is missing");
            }
            return from(catalogue);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static TemplateExamples from(InputStream catalogue) throws IOException {
        List<Entry> entries = new ObjectMapper().readValue(catalogue, new TypeReference<>() {});
        Set<String> ids = new HashSet<>();
        List<TemplateExample> examples = entries.stream().map(entry -> {
            if (!ids.add(entry.id())) {
                throw new IllegalStateException("Example template id " + entry.id() + " is used twice");
            }
            return checked(entry);
        }).toList();
        return new TemplateExamples(examples);
    }

    /** The examples for a channel and category, in catalogue order; null matches every channel or category. */
    public List<TemplateExample> find(Channel channel, MessageCategory category) {
        return examples.stream()
                .filter(example -> channel == null || example.channel() == channel)
                .filter(example -> category == null || example.category() == category)
                .toList();
    }

    private static TemplateExample checked(Entry entry) {
        List<String> variables = List.copyOf(TemplateRenderer.requiredVariables(entry.subject(), entry.body()));
        ChannelRules.Check check = ChannelRules.check(entry.channel(), entry.subject(), entry.body(), variables);
        if (!check.passed() || !check.warnings().isEmpty()) {
            List<String> problems = Stream.concat(check.violations().stream(), check.warnings().stream()).toList();
            throw new IllegalStateException("Example template " + entry.id() + " does not meet the "
                    + entry.channel() + " rules: " + String.join("; ", problems));
        }
        return new TemplateExample(entry.id(), entry.channel(), entry.category(), entry.name(), entry.description(),
                entry.subject(), entry.body(), variables, check.smsLength());
    }
}
