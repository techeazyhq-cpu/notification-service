/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.drafting;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.techeazy.notification.application.ProviderDestinationPolicy;
import com.techeazy.notification.drafting.DraftEvaluation.EvaluationCase;
import com.techeazy.notification.drafting.DraftEvaluation.Report;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the prompt evaluation against a real model (ADR-047). It spends tokens, so it never runs with the build: the
 * class name is outside Surefire's test patterns and it runs only when named, for example against a local Ollama:
 *
 * <pre>
 * mvn -pl template-drafting -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DraftEvaluationRun \
 *     -Ddrafting.evaluation.provider=OLLAMA -Ddrafting.evaluation.model=llama3.2:3b
 * </pre>
 *
 * <p>Settings, as system properties: {@code drafting.evaluation.provider} (an {@link LlmProvider}, default
 * {@code OLLAMA}), {@code .base-url} (default the provider's own, or {@code http://localhost:11434/v1} for Ollama),
 * {@code .model} (default {@code llama3.2:3b}), {@code .api-key} (default the {@code TEMPLATE_DRAFTING_API_KEY}
 * environment variable), {@code .effort} (Anthropic only, default {@code low}), {@code .cases} (a comma-separated list
 * of brief ids, default all) and {@code .min-pass-rate} (default {@code 0.9}). The report is printed and written to
 * {@code target/drafting-evaluation/}; the run fails below the minimum pass rate.
 */
class DraftEvaluationRun {

    private static final String PREFIX = "drafting.evaluation.";
    private static final String OLLAMA_ON_THIS_MACHINE = "http://localhost:11434/v1";
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @Test
    void evaluateTheConfiguredModel() throws Exception {
        LlmProvider provider = LlmProvider.valueOf(setting("provider", "OLLAMA"));
        String model = setting("model", "llama3.2:3b");
        String baseUrl = setting("base-url", provider == LlmProvider.OLLAMA ? OLLAMA_ON_THIS_MACHINE : "");
        String apiKey = setting("api-key", System.getenv().getOrDefault("TEMPLATE_DRAFTING_API_KEY", ""));
        DraftingSettings settings = new DraftingSettings(true, provider, baseUrl, apiKey, model,
                setting("effort", "low"), new DraftingProperties().getMaxTokens(), 0, 0, 600);

        Report report = DraftEvaluation.run(selectedCases(), modelFor(settings), provider + " " + model);

        String markdown = report.toMarkdown();
        System.out.println(markdown);
        Path reports = Files.createDirectories(Path.of("target", "drafting-evaluation"));
        Files.writeString(reports.resolve(model.replaceAll("[^A-Za-z0-9._-]", "_") + ".md"), markdown);
        double minimumPassRate = Double.parseDouble(setting("min-pass-rate", "0.9"));
        assertThat(report.passRate()).as("pass rate of %s; see the report above", model)
                .isGreaterThanOrEqualTo(minimumPassRate);
    }

    private static List<EvaluationCase> selectedCases() {
        List<EvaluationCase> all = DraftEvaluation.loadCases();
        String ids = setting("cases", "");
        if (ids.isBlank()) {
            return all;
        }
        Set<String> wanted = Set.copyOf(Arrays.stream(ids.split(",")).map(String::strip).toList());
        return all.stream().filter(evaluationCase -> wanted.contains(evaluationCase.id())).toList();
    }

    private static DraftModel modelFor(DraftingSettings settings) {
        ProviderDestinationPolicy destinations = new ProviderDestinationPolicy(
                Set.of("localhost", "127.0.0.1", "host.docker.internal"), true,
                host -> List.of(InetAddress.getAllByName(host)));
        DraftModels models = new LlmDraftModels(current -> AnthropicOkHttpClient.builder()
                .apiKey(current.apiKey())
                .baseUrl(current.endpoint())
                .timeout(TIMEOUT)
                .maxRetries(1)
                .build(),
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), TIMEOUT, true, destinations);
        return models.forSettings(settings).orElseThrow(() -> new IllegalStateException(settings.provider()
                + " needs an API key: set -D" + PREFIX + "api-key or TEMPLATE_DRAFTING_API_KEY"));
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getProperty(PREFIX + name);
        return value == null || value.isBlank() ? defaultValue : value.strip();
    }
}
