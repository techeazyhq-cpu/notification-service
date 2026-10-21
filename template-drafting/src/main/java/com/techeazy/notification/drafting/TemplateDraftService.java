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
import com.techeazy.notification.drafting.DraftingException.Reason;
import com.techeazy.notification.drafting.DraftUsageLedger.Outcome;
import com.techeazy.notification.port.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Drafts a template from an author's brief (ADR-041). In order: drafting must be on for the caller, the brief must
 * be valid, the caller must be within its per-minute rate limit, and the platform's and (for a client) the client's
 * monthly token budgets must not be spent. The model's reply is then checked against the channel's rules; a reply
 * that breaks them is sent back once with the broken rules listed, and if the correction breaks them too no draft is
 * returned. Every call that reached the model is written to the {@link DraftUsageLedger} with its tokens.
 *
 * <p>Nothing is saved as a template and nothing is sent: the author saves the draft through the template endpoints.
 */
public class TemplateDraftService {

    public static final int PURPOSE_MIN_LENGTH = 10;
    public static final int PURPOSE_MAX_LENGTH = 1000;
    public static final int MAX_VARIABLES = 15;
    public static final String DRAFTS_METRIC = "notification.drafting.drafts";
    public static final String TOKENS_METRIC = "notification.drafting.tokens";

    private static final Logger LOG = LoggerFactory.getLogger(TemplateDraftService.class);
    private static final Pattern VARIABLE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.\\-]{0,39}$");

    private final DraftingProperties properties;
    private final DraftModel model;
    private final DraftUsageLedger ledger;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Counter tokens;

    public TemplateDraftService(DraftingProperties properties, Optional<DraftModel> model, DraftUsageLedger ledger,
                                RateLimiter rateLimiter, Clock clock, MeterRegistry meters) {
        this.properties = properties;
        this.model = model.orElse(null);
        this.ledger = ledger;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.meters = meters;
        this.tokens = Counter.builder(TOKENS_METRIC)
                .description("Tokens, input and output, spent on drafting templates")
                .register(meters);
    }

    /** Whether this caller may draft at all: a model is configured and, for a client, clients have a budget. */
    public boolean enabledFor(DraftActor actor) {
        return model != null && (!actor.isClient() || properties.getClientMonthlyTokenBudget() > 0);
    }

    public DraftingStatus status(DraftActor actor) {
        if (!enabledFor(actor)) {
            return new DraftingStatus(false, null, 0, properties.getRequestsPerMinute());
        }
        return new DraftingStatus(true, properties.getModel(), tokensLeft(actor), properties.getRequestsPerMinute());
    }

    public TemplateDraft draft(DraftActor actor, DraftRequest request) {
        if (!enabledFor(actor)) {
            throw refused(Reason.DISABLED, "Template drafting is not available");
        }
        validate(request);
        RateLimiter.Decision decision = rateLimiter.tryAcquire(actor.rateLimitKey(),
                properties.getRequestsPerMinute() / 60.0, properties.getRequestsPerMinute());
        if (!decision.allowed()) {
            long retryAfter = Math.max(1, (decision.waitMillis() + 999) / 1000);
            count(Reason.RATE_LIMITED.name());
            throw new DraftingException(Reason.RATE_LIMITED, "Too many drafts; try again in " + retryAfter + " s",
                    List.of(), retryAfter);
        }
        if (tokensLeft(actor) <= 0) {
            throw refused(Reason.BUDGET_EXHAUSTED, actor.isClient()
                    ? "This month's drafting budget is spent; it renews on the first of next month (UTC)"
                    : "The platform's drafting budget for this month is spent; raise "
                            + "template-drafting.monthly-token-budget or wait for next month (UTC)");
        }
        return attempt(actor, request);
    }

    private TemplateDraft attempt(DraftActor actor, DraftRequest request) {
        DraftModel.Prompt prompt = new DraftModel.Prompt(DraftPrompts.SYSTEM, DraftPrompts.brief(request));
        long inputTokens = 0;
        long outputTokens = 0;
        String servedBy = properties.getModel();
        List<String> violations = List.of();
        for (int attempt = 1; attempt <= Math.max(1, properties.getMaxAttempts()); attempt++) {
            DraftModel.Reply reply;
            try {
                reply = model.complete(prompt);
            } catch (DraftModelUnavailableException failure) {
                if (inputTokens + outputTokens > 0) {
                    record(actor, request, servedBy, inputTokens, outputTokens, Outcome.FAILED);
                }
                LOG.warn("Template drafting failed: {}", failure.getMessage());
                throw refused(Reason.UNAVAILABLE, "The drafting model is not reachable right now; try again shortly");
            }
            inputTokens += reply.inputTokens();
            outputTokens += reply.outputTokens();
            servedBy = reply.model() == null ? servedBy : reply.model();
            if (reply.declined()) {
                record(actor, request, servedBy, inputTokens, outputTokens, Outcome.DECLINED);
                throw refused(Reason.DECLINED, "The model declined this brief; describe the message differently");
            }
            String subject = normaliseSubject(reply.subject());
            String body = reply.body() == null ? null : reply.body().strip();
            ChannelRules.Check check = body == null
                    ? new ChannelRules.Check(List.of("The reply was not a readable draft"), List.of(), null)
                    : ChannelRules.check(request.channel(), subject, body, request.variables());
            if (check.passed()) {
                record(actor, request, servedBy, inputTokens, outputTokens, Outcome.DRAFTED);
                count(Outcome.DRAFTED.name());
                return new TemplateDraft(request.channel(), subject, body,
                        List.copyOf(TemplateRenderer.requiredVariables(subject, body)), check.warnings(),
                        check.smsLength(), servedBy);
            }
            violations = check.violations();
            prompt = new DraftModel.Prompt(DraftPrompts.SYSTEM,
                    DraftPrompts.correction(request, subject, body, violations));
        }
        record(actor, request, servedBy, inputTokens, outputTokens, Outcome.OUTPUT_REJECTED);
        count(Outcome.OUTPUT_REJECTED.name());
        throw new DraftingException(Reason.OUTPUT_REJECTED,
                "The model could not produce a draft that meets the " + request.channel() + " rules; try again or "
                        + "simplify the brief", violations, 0);
    }

    private static String normaliseSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        return subject.strip();
    }

    private void validate(DraftRequest request) {
        if (request.channel() == null || !ChannelRules.SUPPORTED.contains(request.channel())) {
            throw refused(Reason.INVALID_REQUEST, "channel must be SMS, EMAIL or WHATSAPP");
        }
        String purpose = request.purpose();
        if (purpose == null || purpose.length() < PURPOSE_MIN_LENGTH || purpose.length() > PURPOSE_MAX_LENGTH) {
            throw refused(Reason.INVALID_REQUEST, "purpose must describe the message in " + PURPOSE_MIN_LENGTH + " to "
                    + PURPOSE_MAX_LENGTH + " characters");
        }
        if (request.variables().size() > MAX_VARIABLES) {
            throw refused(Reason.INVALID_REQUEST, "at most " + MAX_VARIABLES + " variables");
        }
        HashSet<String> seen = new HashSet<>();
        for (String name : request.variables()) {
            if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
                throw refused(Reason.INVALID_REQUEST, "variable names are 1-40 letters, digits, '.', '-' or '_', "
                        + "starting with a letter, without braces: " + name);
            }
            if (!seen.add(name)) {
                throw refused(Reason.INVALID_REQUEST, "variable " + name + " is listed twice");
            }
        }
    }

    private long tokensLeft(DraftActor actor) {
        Instant monthStart = YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        long platformLeft = properties.getMonthlyTokenBudget() - ledger.tokensSince(monthStart);
        if (!actor.isClient()) {
            return Math.max(0, platformLeft);
        }
        long clientLeft = properties.getClientMonthlyTokenBudget() - ledger.tokensSince(actor.clientId(), monthStart);
        return Math.max(0, Math.min(platformLeft, clientLeft));
    }

    private void record(DraftActor actor, DraftRequest request, String servedBy, long inputTokens, long outputTokens,
                        Outcome outcome) {
        tokens.increment(inputTokens + outputTokens);
        ledger.record(new DraftUsageLedger.Usage(UUID.randomUUID(), clock.instant(), actor.name(), actor.clientId(),
                request.channel(), servedBy, inputTokens, outputTokens, outcome));
    }

    private DraftingException refused(Reason reason, String message) {
        count(reason.name());
        return new DraftingException(reason, message);
    }

    private void count(String outcome) {
        Counter.builder(DRAFTS_METRIC)
                .description("Template drafts asked for, by how they ended")
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
