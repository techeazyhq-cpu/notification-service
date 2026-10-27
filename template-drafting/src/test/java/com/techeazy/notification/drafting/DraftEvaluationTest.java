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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.drafting.DraftEvaluation.CaseResult;
import com.techeazy.notification.drafting.DraftEvaluation.EvaluationCase;
import com.techeazy.notification.drafting.DraftEvaluation.Report;
import com.techeazy.notification.drafting.DraftEvaluation.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the prompt evaluation scores a model (ADR-047), with a scripted model in place of a real one, and that the
 * evaluation set itself is sound. Nothing here calls a model; {@code DraftEvaluationRun} does, on request.
 */
class DraftEvaluationTest {

    private static final EvaluationCase OTP_SMS = new EvaluationCase("sms-otp", Channel.SMS, MessageCategory.OTP,
            DraftTone.PROFESSIONAL, "Send the one-time code a customer needs to verify their phone number",
            List.of("code"), List.of("code"));

    private final ScriptedModel model = new ScriptedModel();

    private CaseResult evaluate(EvaluationCase evaluationCase) {
        return DraftEvaluation.run(List.of(evaluationCase), model, "scripted").results().getFirst();
    }

    @Test
    void aDraftThatMeetsEveryExpectationFirstTimePasses() {
        model.replies.add(reply("Your verification code is {{code}}. Do not share it."));

        CaseResult result = evaluate(OTP_SMS);

        assertThat(result.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(result.modelCalls()).isEqualTo(1);
        assertThat(result.tokens()).isEqualTo(150);
        assertThat(result.problems()).isEmpty();
    }

    @Test
    void aDraftThatNeededTheCorrectionPassesAfterCorrection() {
        model.replies.add(reply("Call 0800 123 4567 for your code {{code}}."));
        model.replies.add(reply("Your verification code is {{code}}."));

        CaseResult result = evaluate(OTP_SMS);

        assertThat(result.verdict()).isEqualTo(Verdict.PASSED_AFTER_CORRECTION);
        assertThat(result.modelCalls()).isEqualTo(2);
        assertThat(result.tokens()).isEqualTo(300);
    }

    @Test
    void aDraftThatLeavesOutAWordTheBriefNeedsFallsShort() {
        model.replies.add(reply("Welcome aboard, we are glad to have you."));

        CaseResult result = evaluate(new EvaluationCase("sms-welcome", Channel.SMS, MessageCategory.TRANSACTIONAL,
                DraftTone.FRIENDLY, "Welcome a new customer who just signed up", List.of(),
                List.of("welcome", "account")));

        assertThat(result.verdict()).isEqualTo(Verdict.FELL_SHORT);
        assertThat(result.problems()).containsExactly("Does not mention: account");
    }

    /** The brief lists the variables a draft may use, as in the editor, so leaving one out is noted, not failed. */
    @Test
    void aDraftThatLeavesOutAVariablePassesWithTheVariableNoted() {
        model.replies.add(reply("Welcome aboard, your account is ready."));

        CaseResult result = evaluate(new EvaluationCase("sms-welcome", Channel.SMS, MessageCategory.TRANSACTIONAL,
                DraftTone.FRIENDLY, "Welcome a new customer who just signed up", List.of("firstName"),
                List.of("account")));

        assertThat(result.verdict()).isEqualTo(Verdict.PASSED);
        assertThat(result.unusedVariables()).containsExactly("firstName");
    }

    @Test
    void aDraftThatNeverMetTheRulesIsRejected() {
        model.replies.add(reply("Call 0800 123 4567 for your code {{code}}."));
        model.replies.add(reply("Call 0800 123 4567 for your code {{code}}."));

        CaseResult result = evaluate(OTP_SMS);

        assertThat(result.verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(result.problems()).singleElement().asString().contains("phone number");
    }

    @Test
    void aDeclinedBriefAndAnUnreachableModelAreReportedAsSuch() {
        model.replies.add(new DraftModel.Reply(null, null, true, 40, 0, "scripted"));
        assertThat(evaluate(OTP_SMS).verdict()).isEqualTo(Verdict.DECLINED);

        model.unreachable = true;
        assertThat(evaluate(OTP_SMS).verdict()).isEqualTo(Verdict.FAILED);
    }

    @Test
    void theReportGivesThePassRateTheFirstTimeRateAndTheResultsByChannel() {
        model.replies.add(reply("Your verification code is {{code}}."));
        model.replies.add(reply("Call 0800 123 4567 for your code {{code}}."));
        model.replies.add(reply("Your verification code is {{code}}."));
        model.replies.add(reply("Call 0800 123 4567 for {{code}}."));
        model.replies.add(reply("Call 0800 123 4567 for {{code}}."));
        EvaluationCase second = new EvaluationCase("sms-otp-2", Channel.SMS, MessageCategory.OTP, DraftTone.URGENT,
                "Send the one-time code a customer needs to sign in", List.of("code"), List.of("code"));
        EvaluationCase third = new EvaluationCase("sms-otp-3", Channel.SMS, MessageCategory.OTP, DraftTone.FORMAL,
                "Send the one-time code a customer needs to confirm a payment", List.of("code"), List.of("code"));
        EvaluationCase withTwoVariables = new EvaluationCase("sms-otp-4", Channel.SMS, MessageCategory.OTP,
                DraftTone.FORMAL, "Send the one-time code and how long it is valid", List.of("code", "minutes"),
                List.of("code"));
        model.replies.add(reply("Your verification code is {{code}}."));

        Report report = DraftEvaluation.run(List.of(OTP_SMS, second, third, withTwoVariables), model, "scripted");

        assertThat(report.passRate()).isEqualTo(3.0 / 4);
        assertThat(report.firstTimeRate()).isEqualTo(2.0 / 4);
        assertThat(report.totalTokens()).isEqualTo(900);
        assertThat(report.variableCoverage()).isEqualTo(3.0 / 4);
        String markdown = report.toMarkdown();
        assertThat(markdown).contains("scripted").contains("3 of 4 passed (75%)").contains("| SMS | 3 of 4 |")
                .contains("75% of the listed variables used")
                .contains("| sms-otp-3 | SMS | OTP | REJECTED |").contains("| minutes |");
    }

    @Test
    void theEvaluationSetCoversEveryChannelAndCategoryWithBriefsTheServiceAccepts() {
        List<EvaluationCase> cases = DraftEvaluation.loadCases();
        Set<String> ids = new HashSet<>();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(24).allSatisfy(evaluationCase -> {
            assertThat(ids.add(evaluationCase.id())).as("duplicate " + evaluationCase.id()).isTrue();
            assertThat(evaluationCase.purpose()).hasSizeBetween(TemplateDraftService.PURPOSE_MIN_LENGTH,
                    TemplateDraftService.PURPOSE_MAX_LENGTH);
            assertThat(evaluationCase.variables()).hasSizeLessThanOrEqualTo(TemplateDraftService.MAX_VARIABLES)
                    .allSatisfy(name -> assertThat(name).matches("[A-Za-z][A-Za-z0-9_]*"));
            assertThat(evaluationCase.mustMention()).isNotEmpty();
        });
        for (Channel channel : ChannelRules.SUPPORTED) {
            for (MessageCategory category : MessageCategory.values()) {
                assertThat(cases).as(channel + " " + category)
                        .anyMatch(c -> c.channel() == channel && c.category() == category);
            }
        }
    }

    private static DraftModel.Reply reply(String body) {
        return new DraftModel.Reply(null, body, false, 100, 50, "scripted");
    }

    private static final class ScriptedModel implements DraftModel {
        private final Deque<Reply> replies = new ArrayDeque<>();
        private boolean unreachable;

        @Override
        public Reply complete(Prompt prompt) {
            if (unreachable) {
                throw new DraftModelUnavailableException("connection refused", null);
            }
            return replies.removeFirst();
        }
    }
}
