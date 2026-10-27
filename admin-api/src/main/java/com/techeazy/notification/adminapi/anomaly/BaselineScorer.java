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

package com.techeazy.notification.adminapi.anomaly;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Judges one tenant's hour against that tenant's own history (ADR-039). Pure: it reads nothing and stores nothing, so
 * every rule can be tested with plain numbers.
 *
 * <ul>
 *   <li><b>Baseline.</b> A feature is compared with the same hour of the week over the baseline weeks, by its median
 *       and median absolute deviation. One past spike barely moves either, unlike a mean and standard deviation. Only
 *       increases count: traffic stopping is not abuse. Hours since the tenant started without traffic count as zero.
 *       The spread is never taken below a share of the median, so a perfectly steady tenant is not flagged for a small
 *       wobble, and a value below the feature's floor is never flagged, so 2 messages becoming 9 is not news.</li>
 *   <li><b>Cold start.</b> A tenant with less history than the cold-start period has no baseline yet. Only its hourly
 *       messages are checked, against a fixed per-channel limit, scaled so the limit scores the warning threshold and
 *       twice the limit the high one.</li>
 *   <li><b>New destination.</b> SMS or WhatsApp traffic to a calling code the tenant has not used in the baseline
 *       period is always high severity: it is the clearest sign of SMS pumping.</li>
 * </ul>
 */
final class BaselineScorer {

    private static final double MAD_TO_STANDARD_DEVIATION = 1.4826;
    private static final double MIN_COUNT_SPREAD = 1.0;
    private static final double MIN_RATE_SPREAD = 0.02;
    private static final int MIN_RATE_SAMPLES = 2;
    private static final int DETAIL_LENGTH = 200;
    private static final Duration WEEK = Duration.ofDays(7);
    private static final Set<String> PHONE_CHANNELS = Set.of("SMS", "WHATSAPP");

    private final AnomalyProperties.Scoring settings;

    BaselineScorer(AnomalyProperties.Scoring settings) {
        this.settings = settings;
    }

    /**
     * Scores the chosen features of one hour.
     *
     * @param current  the hour to judge
     * @param history  the tenant's earlier traffic on the same channel
     * @param features which features to judge
     * @return what stood out, in feature order; empty when nothing did
     */
    List<Anomaly> score(TrafficHour current, TenantHistory history, Set<Feature> features) {
        if (isColdStart(current.hour(), history.firstSeen())) {
            return features.contains(Feature.MESSAGES) ? coldStart(current).stream().toList() : List.of();
        }
        List<Anomaly> found = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (features.contains(feature)) {
                judge(feature, current, history).ifPresent(found::add);
            }
        }
        return found;
    }

    private Optional<Anomaly> judge(Feature feature, TrafficHour current, TenantHistory history) {
        return switch (feature) {
            case MESSAGES -> baseline(current, feature, current.messages(),
                    weeklySamples(current, history, TrafficHour::messages), settings.getMessagesFloor(), MIN_COUNT_SPREAD);
            case NEW_RECIPIENTS -> baseline(current, feature, current.newRecipients(),
                    weeklySamples(current, history, TrafficHour::newRecipients), settings.getNewRecipientsFloor(), MIN_COUNT_SPREAD);
            case LARGEST_REQUEST -> baseline(current, feature, current.largestRequest(),
                    weeklySamples(current, history, TrafficHour::largestRequest), settings.getLargestRequestFloor(), MIN_COUNT_SPREAD);
            case FAILURE_RATE -> failureRate(current, history);
            case NEW_DESTINATION -> newDestination(current, history);
        };
    }

    private Optional<Anomaly> coldStart(TrafficHour current) {
        Integer limit = settings.getColdStartMessagesPerHour().get(current.channel());
        if (limit == null || limit <= 0 || current.messages() < limit) {
            return Optional.empty();
        }
        double score = settings.getWarningScore() * current.messages() / limit;
        return anomaly(current, Feature.MESSAGES, DetectionMethod.COLD_START, limit, current.messages(), score, null);
    }

    private Optional<Anomaly> baseline(TrafficHour current, Feature feature, double actual, double[] samples,
                                       double floor, double minSpread) {
        if (actual < floor || samples.length == 0) {
            return Optional.empty();
        }
        double median = median(samples);
        double deviation = median(Arrays.stream(samples).map(sample -> Math.abs(sample - median)).toArray());
        double spread = Math.max(deviation, Math.max(settings.getMinSpreadRatio() * median, minSpread));
        double score = (actual - median) / (MAD_TO_STANDARD_DEVIATION * spread);
        return anomaly(current, feature, DetectionMethod.BASELINE, median, actual, score, null);
    }

    private Optional<Anomaly> failureRate(TrafficHour current, TenantHistory history) {
        int minMessages = settings.getFailureRateMinMessages();
        if (current.messages() < Math.max(minMessages, 1)) {
            return Optional.empty();
        }
        double[] rates = history.sameHourOfWeek().stream()
                .filter(past -> !past.hour().isBefore(history.firstSeen()) && past.messages() >= Math.max(minMessages, 1))
                .mapToDouble(past -> (double) past.failed() / past.messages())
                .toArray();
        if (rates.length < MIN_RATE_SAMPLES) {
            return Optional.empty();
        }
        double rate = (double) current.failed() / current.messages();
        return baseline(current, Feature.FAILURE_RATE, rate, rates, settings.getFailureRateFloor(), MIN_RATE_SPREAD);
    }

    private Optional<Anomaly> newDestination(TrafficHour current, TenantHistory history) {
        if (!PHONE_CHANNELS.contains(current.channel())) {
            return Optional.empty();
        }
        Map<String, Integer> unseen = current.destinations().entrySet().stream()
                .filter(entry -> !history.knownDestinations().contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        int messages = unseen.values().stream().mapToInt(Integer::intValue).sum();
        if (unseen.isEmpty() || messages < settings.getNewDestinationMinMessages()) {
            return Optional.empty();
        }
        String codes = String.join(", ", new TreeSet<>(unseen.keySet()));
        String detail = codes.length() <= DETAIL_LENGTH ? codes : codes.substring(0, DETAIL_LENGTH);
        return anomaly(current, Feature.NEW_DESTINATION, DetectionMethod.NEW_DESTINATION, 0, messages,
                settings.getHighScore(), detail);
    }

    private double[] weeklySamples(TrafficHour current, TenantHistory history, ToIntFunction<TrafficHour> value) {
        return weeklySamples(current.hour(), history.firstSeen(), byHour(history.sameHourOfWeek()),
                settings.getBaselineWeeks(), value);
    }

    /**
     * Whether an hour is still in the tenant's cold start, judged by fixed limits rather than a baseline.
     *
     * @param hour      the hour being judged
     * @param firstSeen the tenant's first measured hour on the channel
     * @return true while the tenant has less history than the cold-start period
     */
    boolean isColdStart(Instant hour, Instant firstSeen) {
        return Duration.between(firstSeen, hour).compareTo(Duration.ofDays(settings.getColdStartDays())) < 0;
    }

    /**
     * The baseline an hour's messages are compared with: their median over the same hour of the week in the baseline
     * weeks, the {@code expected} of a baseline anomaly.
     *
     * @param hour      the hour being judged
     * @param firstSeen the tenant's first measured hour on the channel
     * @param byHour    the tenant's measured hours on the channel, by hour; at least the baseline weeks before
     * @return the expected messages, or empty while the tenant is in its cold start
     */
    OptionalDouble expectedMessages(Instant hour, Instant firstSeen, Map<Instant, TrafficHour> byHour) {
        if (isColdStart(hour, firstSeen)) {
            return OptionalDouble.empty();
        }
        double[] samples = weeklySamples(hour, firstSeen, byHour, settings.getBaselineWeeks(), TrafficHour::messages);
        return samples.length == 0 ? OptionalDouble.empty() : OptionalDouble.of(median(samples));
    }

    static Map<Instant, TrafficHour> byHour(Collection<TrafficHour> hours) {
        return hours.stream().collect(Collectors.toMap(TrafficHour::hour, past -> past, (first, second) -> first));
    }

    private static double[] weeklySamples(Instant hour, Instant firstSeen, Map<Instant, TrafficHour> byHour, int weeks,
                                          ToIntFunction<TrafficHour> value) {
        List<Double> samples = new ArrayList<>();
        for (int week = 1; week <= weeks; week++) {
            Instant slot = hour.minus(WEEK.multipliedBy(week));
            if (!slot.isBefore(firstSeen)) {
                TrafficHour past = byHour.get(slot);
                samples.add(past == null ? 0.0 : value.applyAsInt(past));
            }
        }
        return samples.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private Optional<Anomaly> anomaly(TrafficHour current, Feature feature, DetectionMethod method, double expected,
                                      double actual, double score, String detail) {
        return severity(score).map(severity -> new Anomaly(current.clientId(), current.channel(), current.hour(), feature,
                method, expected, actual, score, severity, detail));
    }

    private Optional<Severity> severity(double score) {
        if (score >= settings.getHighScore()) {
            return Optional.of(Severity.HIGH);
        }
        return score >= settings.getWarningScore() ? Optional.of(Severity.WARNING) : Optional.empty();
    }

    static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }
}
