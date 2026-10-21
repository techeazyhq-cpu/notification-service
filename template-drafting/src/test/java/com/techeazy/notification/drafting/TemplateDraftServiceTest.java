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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.drafting.DraftingException.Reason;
import com.techeazy.notification.drafting.DraftUsageLedger.Outcome;
import com.techeazy.notification.port.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The service's rules, budget and rate limit, with a scripted model in place of Claude: no API is called. */
class TemplateDraftServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final DraftActor ADMIN = DraftActor.administrator("alice");
    private static final DraftActor CLIENT = DraftActor.client(UUID.randomUUID(), "acme");
    private static final DraftRequest SMS_BRIEF = new DraftRequest(Channel.SMS, MessageCategory.TRANSACTIONAL,
            DraftTone.FRIENDLY, "Remind the customer of their appointment tomorrow", List.of("firstName", "time"));

    private final ScriptedModel model = new ScriptedModel();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final DraftingProperties properties = new DraftingProperties();
    private final List<DraftingSettings> settingsGivenToTheModel = new ArrayList<>();
    private boolean switchedOn = true;
    private RateLimiter.Decision rateDecision = RateLimiter.Decision.GRANTED;
    private final RateLimiter rateLimiter = (key, rate, burst, reserve) -> rateDecision;

    private TemplateDraftService service() {
        return service(Optional.of(model));
    }

    private TemplateDraftService service(Optional<DraftModel> withModel) {
        properties.setApiKey("sk-ant-test-key");
        DraftingSettingsSource settings = () -> DraftingSettings.from(properties).withEnabled(switchedOn);
        DraftModels models = current -> {
            settingsGivenToTheModel.add(current);
            return withModel;
        };
        return new TemplateDraftService(properties, settings, models, ledger, rateLimiter,
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    @Test
    void theSettingsAreReadForEveryDraftSoAChangeInTheConsoleAppliesAtOnce() {
        TemplateDraftService service = service();
        model.replies.add(reply(null, "Hi {{firstName}}, see you at {{time}}.", 1, 1));
        service.draft(ADMIN, SMS_BRIEF);

        properties.setModel("claude-sonnet-5-5");
        properties.setRequestsPerMinute(9);

        assertThat(service.status(ADMIN).model()).isEqualTo("claude-sonnet-5-5");
        assertThat(service.status(ADMIN).requestsPerMinute()).isEqualTo(9);
        assertThat(settingsGivenToTheModel.getFirst().model()).isEqualTo("claude-opus-5-5");
        assertThat(settingsGivenToTheModel.getLast().model()).isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void switchedOffInTheConsoleDraftingIsOffEvenWithAKey() {
        switchedOn = false;

        assertThat(service().status(ADMIN).enabled()).isFalse();
        assertThatThrownBy(() -> service().draft(ADMIN, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.DISABLED));
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void aDraftThatMeetsTheRulesIsReturnedAndItsTokensRecorded() {
        model.replies.add(reply(null, "Hi {{firstName}}, see you tomorrow at {{time}}.", 300, 120));

        TemplateDraft draft = service().draft(ADMIN, SMS_BRIEF);

        assertThat(draft.body()).isEqualTo("Hi {{firstName}}, see you tomorrow at {{time}}.");
        assertThat(draft.subject()).isNull();
        assertThat(draft.variables()).containsExactly("firstName", "time");
        assertThat(draft.smsLength().segments()).isEqualTo(1);
        assertThat(ledger.usages).singleElement().satisfies(usage -> {
            assertThat(usage.outcome()).isEqualTo(Outcome.DRAFTED);
            assertThat(usage.inputTokens()).isEqualTo(300);
            assertThat(usage.outputTokens()).isEqualTo(120);
            assertThat(usage.actor()).isEqualTo("alice");
            assertThat(usage.clientId()).isNull();
        });
    }

    @Test
    void onlyTheBriefAndThePlaceholderNamesReachTheModel() {
        model.replies.add(reply(null, "Hi {{firstName}}, see you at {{time}}.", 1, 1));

        service().draft(ADMIN, SMS_BRIEF);

        DraftModel.Prompt prompt = model.prompts.getFirst();
        assertThat(prompt.system()).isEqualTo(DraftPrompts.SYSTEM);
        assertThat(prompt.user()).contains("<brief>\nRemind the customer of their appointment tomorrow\n</brief>")
                .contains("{{firstName}}, {{time}}").contains("Channel: SMS");
    }

    @Test
    void aReplyThatBreaksTheRulesIsCorrectedOnce() {
        model.replies.add(reply(null, "Hi {{name}}, see you at {{time}}.", 100, 50));
        model.replies.add(reply(null, "Hi {{firstName}}, see you at {{time}}.", 150, 40));

        TemplateDraft draft = service().draft(ADMIN, SMS_BRIEF);

        assertThat(draft.body()).contains("{{firstName}}");
        assertThat(model.prompts.get(1).user()).contains("Uses placeholders that were not asked for: name")
                .contains("<previous_draft>\nHi {{name}}, see you at {{time}}.\n</previous_draft>");
        assertThat(ledger.usages).singleElement().satisfies(usage -> {
            assertThat(usage.inputTokens()).isEqualTo(250);
            assertThat(usage.outputTokens()).isEqualTo(90);
        });
    }

    @Test
    void whenTheCorrectionAlsoBreaksTheRulesNoDraftIsReturned() {
        model.replies.add(reply(null, "a".repeat(500), 10, 10));
        model.replies.add(reply(null, "b".repeat(500), 10, 10));

        assertThatThrownBy(() -> service().draft(ADMIN, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.OUTPUT_REJECTED);
                    assertThat(e.violations()).anyMatch(v -> v.contains("SMS segments"));
                    assertThat(e.describe()).contains("SMS segments");
                });
        assertThat(model.prompts).hasSize(2);
        assertThat(ledger.usages).singleElement().extracting(DraftUsageLedger.Usage::outcome)
                .isEqualTo(Outcome.OUTPUT_REJECTED);
    }

    @Test
    void anUnreadableReplyCountsAsBreakingTheRules() {
        model.replies.add(reply(null, null, 10, 8000));
        model.replies.add(reply(null, "Hi {{firstName}}, at {{time}}.", 10, 10));

        assertThat(service().draft(ADMIN, SMS_BRIEF).body()).isEqualTo("Hi {{firstName}}, at {{time}}.");
        assertThat(model.prompts.get(1).user()).contains("The reply was not a readable draft");
    }

    @Test
    void anEmailDraftKeepsItsSubject() {
        model.replies.add(reply(" Your appointment ", "<p>Hi {{firstName}}</p>", 1, 1));
        DraftRequest email = new DraftRequest(Channel.EMAIL, null, null, "Confirm an appointment booking",
                List.of("firstName"));

        TemplateDraft draft = service().draft(ADMIN, email);

        assertThat(draft.subject()).isEqualTo("Your appointment");
        assertThat(draft.smsLength()).isNull();
    }

    @Test
    void aDeclinedBriefIsRecordedAndReported() {
        model.replies.add(new DraftModel.Reply(null, null, true, 50, 5, "claude-opus-5-5"));

        assertThatThrownBy(() -> service().draft(ADMIN, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DECLINED));
        assertThat(ledger.usages).singleElement().extracting(DraftUsageLedger.Usage::outcome)
                .isEqualTo(Outcome.DECLINED);
    }

    @Test
    void anUnreachableModelIsReportedAsUnavailableWithoutALedgerRow() {
        model.failure = new DraftModelUnavailableException("timeout", null);

        assertThatThrownBy(() -> service().draft(ADMIN, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNAVAILABLE));
        assertThat(ledger.usages).isEmpty();
    }

    @Test
    void withoutAModelDraftingIsOffAndTheStatusSaysSo() {
        TemplateDraftService disabled = service(Optional.empty());

        assertThat(disabled.status(ADMIN).enabled()).isFalse();
        assertThatThrownBy(() -> disabled.draft(ADMIN, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class, e -> assertThat(e.reason()).isEqualTo(Reason.DISABLED));
    }

    @Test
    void aClientBudgetOfZeroKeepsDraftingForAdministratorsOnly() {
        properties.setClientMonthlyTokenBudget(0);

        assertThat(service().enabledFor(ADMIN)).isTrue();
        assertThat(service().enabledFor(CLIENT)).isFalse();
    }

    @Test
    void theRateLimitIsCheckedPerCallerBeforeTheModelIsAsked() {
        rateDecision = new RateLimiter.Decision(false, 4200);

        assertThatThrownBy(() -> service().draft(CLIENT, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.RATE_LIMITED);
                    assertThat(e.retryAfterSeconds()).isEqualTo(5);
                });
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void aSpentClientBudgetRefusesThatClientOnly() {
        properties.setClientMonthlyTokenBudget(1000);
        ledger.usages.add(usage(CLIENT.clientId(), NOW.minusSeconds(60), 1000));
        model.replies.add(reply(null, "Hi {{firstName}}, at {{time}}.", 1, 1));

        assertThatThrownBy(() -> service().draft(CLIENT, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.BUDGET_EXHAUSTED));
        assertThat(service().draft(DraftActor.client(UUID.randomUUID(), "other"), SMS_BRIEF)).isNotNull();
    }

    @Test
    void aSpentPlatformBudgetRefusesEveryone() {
        properties.setMonthlyTokenBudget(500);
        ledger.usages.add(usage(null, NOW.minusSeconds(60), 500));

        assertThatThrownBy(() -> service().draft(ADMIN, SMS_BRIEF))
                .isInstanceOfSatisfying(DraftingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.BUDGET_EXHAUSTED));
    }

    @Test
    void lastMonthsSpendDoesNotCount() {
        properties.setMonthlyTokenBudget(500);
        ledger.usages.add(usage(null, Instant.parse("2026-09-30T23:59:59Z"), 500));

        assertThat(service().status(ADMIN).tokensLeftThisMonth()).isEqualTo(500);
    }

    @Test
    void aClientSeesTheSmallerOfItsOwnAndThePlatformsRemainingBudget() {
        properties.setMonthlyTokenBudget(10_000);
        properties.setClientMonthlyTokenBudget(3000);
        ledger.usages.add(usage(CLIENT.clientId(), NOW, 1000));
        ledger.usages.add(usage(null, NOW, 8500));

        assertThat(service().status(CLIENT).tokensLeftThisMonth()).isEqualTo(500);
        assertThat(service().status(DraftActor.client(UUID.randomUUID(), "other")).tokensLeftThisMonth())
                .isEqualTo(500);
    }

    @Test
    void invalidBriefsAreRefusedBeforeAnythingElse() {
        assertInvalid(new DraftRequest(Channel.PUSH, null, null, "Tell users about the sale", List.of()));
        assertInvalid(new DraftRequest(null, null, null, "Tell users about the sale", List.of()));
        assertInvalid(new DraftRequest(Channel.SMS, null, null, "short", List.of()));
        assertInvalid(new DraftRequest(Channel.SMS, null, null, "x".repeat(1001), List.of()));
        assertInvalid(new DraftRequest(Channel.SMS, null, null, "Tell users about the sale", List.of("{{name}}")));
        assertInvalid(new DraftRequest(Channel.SMS, null, null, "Tell users about the sale", List.of("a", "a")));
        assertInvalid(new DraftRequest(Channel.SMS, null, null, "Tell users about the sale",
                java.util.stream.IntStream.range(0, 16).mapToObj(i -> "v" + i).toList()));
        assertThat(model.prompts).isEmpty();
    }

    private void assertInvalid(DraftRequest request) {
        assertThatThrownBy(() -> service().draft(ADMIN, request))
                .isInstanceOfSatisfying(DraftingException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_REQUEST));
    }

    private static DraftModel.Reply reply(String subject, String body, long in, long out) {
        return new DraftModel.Reply(subject, body, false, in, out, "claude-opus-5-5");
    }

    private static DraftUsageLedger.Usage usage(UUID clientId, Instant at, long tokens) {
        return new DraftUsageLedger.Usage(UUID.randomUUID(), at, "someone", clientId, Channel.SMS, "claude-opus-5-5",
                tokens, 0, Outcome.DRAFTED);
    }

    private static final class ScriptedModel implements DraftModel {
        private final Deque<Reply> replies = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();
        private DraftModelUnavailableException failure;

        @Override
        public Reply complete(Prompt prompt) {
            prompts.add(prompt);
            if (failure != null) {
                throw failure;
            }
            return replies.removeFirst();
        }
    }

    private static final class InMemoryLedger implements DraftUsageLedger {
        private final List<Usage> usages = new ArrayList<>();

        @Override
        public void record(Usage usage) {
            usages.add(usage);
        }

        @Override
        public long tokensSince(Instant since) {
            return usages.stream().filter(u -> !u.occurredAt().isBefore(since))
                    .mapToLong(u -> u.inputTokens() + u.outputTokens()).sum();
        }

        @Override
        public long tokensSince(UUID clientId, Instant since) {
            return usages.stream().filter(u -> clientId.equals(u.clientId()) && !u.occurredAt().isBefore(since))
                    .mapToLong(u -> u.inputTokens() + u.outputTokens()).sum();
        }
    }
}
