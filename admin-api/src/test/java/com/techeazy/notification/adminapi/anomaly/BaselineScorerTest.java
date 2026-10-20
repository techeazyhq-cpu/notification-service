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

package com.techeazy.notification.adminapi.anomaly;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BaselineScorerTest {

    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant HOUR = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant LONG_AGO = HOUR.minus(Duration.ofDays(120));
    private static final Set<Feature> ALL = EnumSet.allOf(Feature.class);

    private final AnomalyProperties.Scoring settings = new AnomalyProperties.Scoring();
    private final BaselineScorer scorer = new BaselineScorer(settings);

    @Test
    void aSpikeFarAboveASteadyTenantIsHighWithTheMedianAsExpected() {
        List<Anomaly> found = scorer.score(sms(5000), history(LONG_AGO, weekly(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)),
                Set.of(Feature.MESSAGES));

        assertThat(found).singleElement().satisfies(anomaly -> {
            assertThat(anomaly.feature()).isEqualTo(Feature.MESSAGES);
            assertThat(anomaly.method()).isEqualTo(DetectionMethod.BASELINE);
            assertThat(anomaly.severity()).isEqualTo(Severity.HIGH);
            assertThat(anomaly.expected()).isEqualTo(1000);
            assertThat(anomaly.actual()).isEqualTo(5000);
            assertThat(anomaly.score()).isCloseTo(4000 / (1.4826 * 100), within(1e-9));
            assertThat(anomaly.hour()).isEqualTo(HOUR);
            assertThat(anomaly.clientId()).isEqualTo(CLIENT);
        });
    }

    @Test
    void aScoreBetweenTheThresholdsIsAWarning() {
        List<Anomaly> found = scorer.score(sms(1890), history(LONG_AGO, weekly(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)),
                Set.of(Feature.MESSAGES));

        assertThat(found).singleElement().extracting(Anomaly::severity).isEqualTo(Severity.WARNING);
    }

    @Test
    void ordinaryGrowthIsNotFlagged() {
        List<Anomaly> found = scorer.score(sms(1150), history(LONG_AGO, weekly(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)),
                ALL);

        assertThat(found).isEmpty();
    }

    @Test
    void aDropIsNeverFlagged() {
        List<Anomaly> found = scorer.score(sms(100), history(LONG_AGO, weekly(5000, 5000, 5000, 5000, 5000, 5000, 5000, 5000)),
                ALL);

        assertThat(found).isEmpty();
    }

    @Test
    void oneEarlierSpikeDoesNotRaiseTheBaseline() {
        List<Anomaly> found = scorer.score(sms(5000), history(LONG_AGO, weekly(1000, 50000, 1000, 1000, 1000, 1000, 1000, 1000)),
                Set.of(Feature.MESSAGES));

        assertThat(found).singleElement().satisfies(anomaly -> {
            assertThat(anomaly.expected()).isEqualTo(1000);
            assertThat(anomaly.severity()).isEqualTo(Severity.HIGH);
        });
    }

    @Test
    void aQuietTenantBelowTheFloorIsNotFlaggedHoweverUnusual() {
        List<Anomaly> found = scorer.score(sms(90), history(LONG_AGO, weekly(2, 2, 2, 2, 2, 2, 2, 2)), ALL);

        assertThat(found).isEmpty();
    }

    @Test
    void weeksWithoutTrafficCountAsZero() {
        List<Anomaly> found = scorer.score(sms(500), history(LONG_AGO, List.of()), Set.of(Feature.MESSAGES));

        assertThat(found).singleElement().satisfies(anomaly -> {
            assertThat(anomaly.expected()).isZero();
            assertThat(anomaly.severity()).isEqualTo(Severity.HIGH);
        });
    }

    @Test
    void weeksBeforeTheTenantStartedAreLeftOut() {
        Instant threeWeeksAgo = HOUR.minus(Duration.ofDays(21));

        List<Anomaly> found = scorer.score(sms(5000), history(threeWeeksAgo, weekly(1000, 1000, 1000)),
                Set.of(Feature.MESSAGES));

        assertThat(found).singleElement().extracting(Anomaly::expected).isEqualTo(1000.0);
    }

    @Test
    void aNewTenantIsJudgedAgainstTheChannelLimitScaledToTheSameScores() {
        TenantHistory fiveDays = history(HOUR.minus(Duration.ofDays(5)), List.of());

        assertThat(scorer.score(sms(900), fiveDays, ALL)).isEmpty();
        assertThat(scorer.score(sms(1500), fiveDays, ALL)).singleElement().satisfies(anomaly -> {
            assertThat(anomaly.method()).isEqualTo(DetectionMethod.COLD_START);
            assertThat(anomaly.expected()).isEqualTo(1000);
            assertThat(anomaly.score()).isEqualTo(6.0);
            assertThat(anomaly.severity()).isEqualTo(Severity.WARNING);
        });
        assertThat(scorer.score(sms(2000), fiveDays, ALL)).singleElement()
                .extracting(Anomaly::severity).isEqualTo(Severity.HIGH);
    }

    @Test
    void aNewTenantIsNotFlaggedForNewDestinationsSinceEveryDestinationIsNew() {
        TrafficHour hour = new TrafficHour(CLIENT, "SMS", HOUR, 50, 50, 50, 0, 1, Map.of("882", 50), Map.of());

        assertThat(scorer.score(hour, history(HOUR.minus(Duration.ofDays(5)), List.of()), ALL)).isEmpty();
    }

    @Test
    void smsToACallingCodeTheTenantHasNotUsedIsAlwaysHigh() {
        TrafficHour hour = new TrafficHour(CLIENT, "SMS", HOUR, 530, 530, 0, 0, 1, Map.of("44", 500, "882", 20, "883", 10), Map.of());
        TenantHistory history = new TenantHistory(LONG_AGO, weekly(530, 530, 530, 530, 530, 530, 530, 530), Set.of("44"));

        List<Anomaly> found = scorer.score(hour, history, ALL);

        assertThat(found).singleElement().satisfies(anomaly -> {
            assertThat(anomaly.feature()).isEqualTo(Feature.NEW_DESTINATION);
            assertThat(anomaly.method()).isEqualTo(DetectionMethod.NEW_DESTINATION);
            assertThat(anomaly.severity()).isEqualTo(Severity.HIGH);
            assertThat(anomaly.actual()).isEqualTo(30);
            assertThat(anomaly.detail()).isEqualTo("882, 883");
        });
    }

    @Test
    void aFewMessagesToANewDestinationAreNotFlagged() {
        TrafficHour hour = new TrafficHour(CLIENT, "WHATSAPP", HOUR, 505, 505, 0, 0, 1, Map.of("44", 500, "33", 5), Map.of());
        TenantHistory history = new TenantHistory(LONG_AGO, List.of(), Set.of("44"));

        assertThat(scorer.score(hour, history, Set.of(Feature.NEW_DESTINATION))).isEmpty();
    }

    @Test
    void newEmailDomainsAreNotAnAnomaly() {
        TrafficHour hour = new TrafficHour(CLIENT, "EMAIL", HOUR, 500, 500, 0, 0, 1, Map.of("example.org", 500), Map.of());
        TenantHistory history = new TenantHistory(LONG_AGO, List.of(), Set.of("example.com"));

        assertThat(scorer.score(hour, history, Set.of(Feature.NEW_DESTINATION))).isEmpty();
    }

    @Test
    void aJumpInTheFailureRateIsFlaggedOnceTheHourHasEnoughMessages() {
        List<TrafficHour> past = new ArrayList<>();
        for (int week = 1; week <= 8; week++) {
            past.add(new TrafficHour(CLIENT, "SMS", HOUR.minus(Duration.ofDays(7L * week)), 1000, 1000, 0, 20, 1, Map.of(), Map.of()));
        }
        TenantHistory history = new TenantHistory(LONG_AGO, past, Set.of());

        List<Anomaly> found = scorer.score(failing(1000, 400), history, Feature.SETTLED);

        assertThat(found).singleElement().satisfies(anomaly -> {
            assertThat(anomaly.feature()).isEqualTo(Feature.FAILURE_RATE);
            assertThat(anomaly.expected()).isCloseTo(0.02, within(1e-9));
            assertThat(anomaly.actual()).isCloseTo(0.4, within(1e-9));
            assertThat(anomaly.severity()).isEqualTo(Severity.HIGH);
        });
        assertThat(scorer.score(failing(40, 30), history, Feature.SETTLED)).isEmpty();
    }

    @Test
    void theFailureRateNeedsEnoughComparableHours() {
        TrafficHour onlyOne = new TrafficHour(CLIENT, "SMS", HOUR.minus(Duration.ofDays(7)), 1000, 1000, 0, 20, 1, Map.of(), Map.of());

        assertThat(scorer.score(failing(1000, 400), new TenantHistory(LONG_AGO, List.of(onlyOne), Set.of()), Feature.SETTLED))
                .isEmpty();
    }

    @Test
    void onlyTheRequestedFeaturesAreScored() {
        TenantHistory steady = history(LONG_AGO, weekly(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000));

        assertThat(scorer.score(sms(5000), steady, Feature.SETTLED)).isEmpty();
    }

    @Test
    void theThresholdsComeFromConfiguration() {
        settings.setWarningScore(30);
        settings.setHighScore(60);

        List<Anomaly> found = scorer.score(sms(5000), history(LONG_AGO, weekly(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)),
                Set.of(Feature.MESSAGES));

        assertThat(found).isEmpty();
    }

    private static TrafficHour sms(int messages) {
        return new TrafficHour(CLIENT, "SMS", HOUR, messages, messages, 0, 0, 1, Map.of(), Map.of());
    }

    private static TrafficHour failing(int messages, int failed) {
        return new TrafficHour(CLIENT, "SMS", HOUR, messages, messages, 0, failed, 1, Map.of(), Map.of());
    }

    private static List<TrafficHour> weekly(int... messagesByWeekBack) {
        List<TrafficHour> past = new ArrayList<>();
        for (int i = 0; i < messagesByWeekBack.length; i++) {
            Instant hour = HOUR.minus(Duration.ofDays(7L * (i + 1)));
            int messages = messagesByWeekBack[i];
            past.add(new TrafficHour(CLIENT, "SMS", hour, messages, messages, 0, 0, 1, Map.of(), Map.of()));
        }
        return past;
    }

    private static TenantHistory history(Instant firstSeen, List<TrafficHour> sameHourOfWeek) {
        return new TenantHistory(firstSeen, sameHourOfWeek, Set.of());
    }
}
