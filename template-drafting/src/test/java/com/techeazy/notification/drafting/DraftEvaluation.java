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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.port.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The prompt evaluation (ADR-047): runs every brief of the evaluation set through the same path a real draft takes,
 * {@link TemplateDraftService} with the channel rules and the one correction, and scores what comes back. A draft
 * passes when it meets the rules and mentions the words the brief cannot do without. The variables a brief lists are
 * ones the draft may use, as in the editor, so one left out is counted in the variable coverage rather than failed.
 * Comparing two reports shows whether a model or prompt change is better or worse before it ships.
 */
final class DraftEvaluation {

    static final String CASES_RESOURCE = "draft-evaluation-cases.json";

    /** How one brief ended. */
    enum Verdict {
        /** Met every expectation at the first attempt. */
        PASSED,
        /** Met every expectation, after the correction. */
        PASSED_AFTER_CORRECTION,
        /** Met the rules, but left out a word the brief needs. */
        FELL_SHORT,
        /** Broke the channel's rules even after the correction. */
        REJECTED,
        /** The model declined the brief. */
        DECLINED,
        /** The model could not be reached or failed. */
        FAILED;

        boolean passed() {
            return this == PASSED || this == PASSED_AFTER_CORRECTION;
        }
    }

    /**
     * One brief of the evaluation set.
     *
     * @param variables   the placeholders the draft may use
     * @param mustMention words the draft must contain, ignoring case, for it to say what the brief asked
     */
    record EvaluationCase(String id, Channel channel, MessageCategory category, DraftTone tone, String purpose,
                         List<String> variables, List<String> mustMention) {}

    /**
     * @param modelCalls how many times the model was asked: one, or two with the correction
     * @param problems        what kept the brief from passing; empty when it passed
     * @param unusedVariables the listed variables a draft that met the rules left out
     * @param draft           the body the model wrote, if it met the rules
     */
    record CaseResult(EvaluationCase evaluationCase, Verdict verdict, int modelCalls, long tokens, long millis,
                      List<String> problems, List<String> unusedVariables, String draft) {

        String id() {
            return evaluationCase.id();
        }
    }

    record Report(String model, Instant ranAt, List<CaseResult> results) {

        double passRate() {
            return share(result -> result.verdict().passed());
        }

        double firstTimeRate() {
            return share(result -> result.verdict() == Verdict.PASSED);
        }

        long totalTokens() {
            return results.stream().mapToLong(CaseResult::tokens).sum();
        }

        /** Of the variables listed for briefs that produced a draft, the share the drafts used. */
        double variableCoverage() {
            List<CaseResult> drafted = results.stream().filter(result -> result.draft() != null).toList();
            long listed = drafted.stream().mapToLong(result -> result.evaluationCase().variables().size()).sum();
            long unused = drafted.stream().mapToLong(result -> result.unusedVariables().size()).sum();
            return listed == 0 ? 1 : (double) (listed - unused) / listed;
        }

        private double share(Predicate<CaseResult> counted) {
            return results.isEmpty() ? 0 : (double) results.stream().filter(counted).count() / results.size();
        }

        /** A Markdown report: the totals, the pass count by channel and every brief's outcome. */
        String toMarkdown() {
            long passed = results.stream().filter(result -> result.verdict().passed()).count();
            StringBuilder markdown = new StringBuilder()
                    .append("# Drafting evaluation: ").append(model).append("\n\n")
                    .append("Run at ").append(ranAt).append(". ")
                    .append(passed).append(" of ").append(results.size()).append(" passed (")
                    .append(percent(passRate())).append("), ").append(percent(firstTimeRate()))
                    .append(" at the first attempt; ").append(percent(variableCoverage()))
                    .append(" of the listed variables used; ").append(totalTokens()).append(" tokens.\n\n")
                    .append("| Channel | Passed |\n|---|---|\n");
            for (Channel channel : EnumSet.copyOf(ChannelRules.SUPPORTED)) {
                List<CaseResult> ofChannel = results.stream()
                        .filter(result -> result.evaluationCase().channel() == channel).toList();
                if (!ofChannel.isEmpty()) {
                    long channelPassed = ofChannel.stream().filter(result -> result.verdict().passed()).count();
                    markdown.append("| ").append(channel).append(" | ").append(channelPassed).append(" of ")
                            .append(ofChannel.size()).append(" |\n");
                }
            }
            markdown.append("\n| Brief | Channel | Category | Verdict | Calls | Tokens | Seconds | Unused variables "
                            + "| Problems |\n")
                    .append("|---|---|---|---|---|---|---|---|---|\n");
            for (CaseResult result : results) {
                markdown.append("| ").append(result.id()).append(" | ").append(result.evaluationCase().channel())
                        .append(" | ").append(result.evaluationCase().category()).append(" | ")
                        .append(result.verdict()).append(" | ").append(result.modelCalls()).append(" | ")
                        .append(result.tokens()).append(" | ")
                        .append(String.format(Locale.ROOT, "%.1f", result.millis() / 1000.0)).append(" | ")
                        .append(String.join(", ", result.unusedVariables())).append(" | ")
                        .append(String.join("; ", result.problems()).replace("|", "\\|")).append(" |\n");
            }
            return markdown.toString();
        }

        private static String percent(double rate) {
            return Math.round(rate * 100) + "%";
        }
    }

    private DraftEvaluation() {}

    /** The evaluation set shipped with the tests. */
    static List<EvaluationCase> loadCases() {
        try (InputStream cases = DraftEvaluation.class.getResourceAsStream(CASES_RESOURCE)) {
            if (cases == null) {
                throw new IllegalStateException("The evaluation set " + CASES_RESOURCE + " is missing");
            }
            return new ObjectMapper().readValue(cases, new TypeReference<>() {});
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Drafts every brief with {@code model}, one after another, and scores each. */
    static Report run(List<EvaluationCase> cases, DraftModel model, String modelName) {
        CountingModel counting = new CountingModel(model);
        TemplateDraftService service = serviceFor(counting, modelName);
        DraftActor evaluator = DraftActor.administrator("prompt-evaluation");
        List<CaseResult> results = new ArrayList<>();
        for (EvaluationCase evaluationCase : cases) {
            counting.reset();
            long started = System.nanoTime();
            DraftRequest request = new DraftRequest(evaluationCase.channel(), evaluationCase.category(),
                    evaluationCase.tone(), evaluationCase.purpose(), evaluationCase.variables());
            Outcome outcome = draft(service, evaluator, request, evaluationCase, counting);
            results.add(new CaseResult(evaluationCase, outcome.verdict(), counting.calls(), counting.tokens(),
                    (System.nanoTime() - started) / 1_000_000, outcome.problems(), outcome.unusedVariables(),
                    outcome.draft()));
        }
        return new Report(modelName, Instant.now(), results);
    }

    private record Outcome(Verdict verdict, List<String> problems, List<String> unusedVariables, String draft) {}

    private static Outcome draft(TemplateDraftService service, DraftActor evaluator, DraftRequest request,
                                 EvaluationCase evaluationCase, CountingModel counting) {
        TemplateDraft draft;
        try {
            draft = service.draft(evaluator, request);
        } catch (DraftingException refused) {
            return switch (refused.reason()) {
                case OUTPUT_REJECTED -> new Outcome(Verdict.REJECTED, refused.violations(), List.of(), null);
                case DECLINED -> new Outcome(Verdict.DECLINED, List.of(refused.getMessage()), List.of(), null);
                default -> new Outcome(Verdict.FAILED, List.of(refused.getMessage()), List.of(), null);
            };
        }
        List<String> unused = evaluationCase.variables().stream()
                .filter(variable -> !draft.variables().contains(variable)).toList();
        List<String> problems = shortfalls(draft, evaluationCase);
        if (!problems.isEmpty()) {
            return new Outcome(Verdict.FELL_SHORT, problems, unused, draft.body());
        }
        return new Outcome(counting.calls() > 1 ? Verdict.PASSED_AFTER_CORRECTION : Verdict.PASSED, List.of(),
                unused, draft.body());
    }

    private static List<String> shortfalls(TemplateDraft draft, EvaluationCase evaluationCase) {
        List<String> problems = new ArrayList<>();
        String text = ((draft.subject() == null ? "" : draft.subject()) + "\n" + draft.body()).toLowerCase(Locale.ROOT);
        List<String> unmentioned = evaluationCase.mustMention().stream()
                .filter(word -> !text.contains(word.toLowerCase(Locale.ROOT))).toList();
        if (!unmentioned.isEmpty()) {
            problems.add("Does not mention: " + String.join(", ", unmentioned));
        }
        return problems;
    }

    private static TemplateDraftService serviceFor(DraftModel model, String modelName) {
        DraftingProperties properties = new DraftingProperties();
        DraftingSettings settings = new DraftingSettings(true, LlmProvider.OLLAMA, "http://localhost:11434/v1", null,
                modelName, "low", properties.getMaxTokens(), Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 600);
        RateLimiter unlimited = (key, rate, burst, reserve) -> RateLimiter.Decision.GRANTED;
        return new TemplateDraftService(properties, () -> settings, current -> Optional.of(model), new NoLedger(),
                new NoPolicies(), unlimited, Clock.systemUTC(), new SimpleMeterRegistry());
    }

    /** Counts the calls made to the model and the tokens they spent, per brief. */
    private static final class CountingModel implements DraftModel {
        private final DraftModel model;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong tokens = new AtomicLong();

        CountingModel(DraftModel model) {
            this.model = model;
        }

        @Override
        public Reply complete(Prompt prompt) {
            calls.incrementAndGet();
            Reply reply = model.complete(prompt);
            tokens.addAndGet(reply.inputTokens() + reply.outputTokens());
            return reply;
        }

        int calls() {
            return calls.get();
        }

        long tokens() {
            return tokens.get();
        }

        void reset() {
            calls.set(0);
            tokens.set(0);
        }
    }

    private static final class NoLedger implements DraftUsageLedger {
        @Override
        public void record(Usage usage) {
        }

        @Override
        public long tokensSince(Instant since) {
            return 0;
        }

        @Override
        public long tokensSince(UUID clientId, Instant since) {
            return 0;
        }

        @Override
        public Map<UUID, Long> tokensByClientSince(Instant since) {
            return Map.of();
        }
    }

    private static final class NoPolicies implements ClientDraftingPolicies {
        @Override
        public ClientDraftingPolicy of(UUID clientId) {
            return ClientDraftingPolicy.DEFAULT;
        }

        @Override
        public Map<UUID, ClientDraftingPolicy> all() {
            return Map.of();
        }

        @Override
        public void save(UUID clientId, ClientDraftingPolicy policy, String administrator) {
            throw new UnsupportedOperationException("The evaluation never saves a policy");
        }
    }
}
