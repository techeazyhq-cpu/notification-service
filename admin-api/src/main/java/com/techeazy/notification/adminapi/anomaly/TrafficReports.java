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

import com.techeazy.notification.domain.Channel;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * What the admin console shows about a tenant's traffic (ADR-039): an hourly series with the baseline the scorer
 * compares against, and a weekly report. Both are read from {@code traffic_hourly} and {@code traffic_anomaly} when
 * asked for; nothing is stored.
 */
class TrafficReports {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final int TOP_DESTINATIONS = 10;

    /**
     * One hour of the series.
     *
     * @param hour          the start of the hour, in UTC
     * @param messages      messages accepted
     * @param failed        messages failed so far
     * @param newRecipients recipients the tenant had not sent to recently
     * @param expected      the baseline the scorer compares the hour's messages with, or {@code null} while the tenant
     *                      is in its cold start
     */
    record Point(Instant hour, int messages, int failed, int newRecipients, Double expected) {
    }

    /**
     * A tenant's hourly traffic on one channel, with its anomalies.
     *
     * @param clientId   the tenant
     * @param clientName its name
     * @param channel    the channel's name
     * @param from       the first hour, inclusive
     * @param to         the end, exclusive: the start of the current hour
     * @param points     every hour of the period, including those without traffic
     * @param anomalies  the anomalies of the period, oldest first
     */
    record Series(UUID clientId, String clientName, String channel, Instant from, Instant to, List<Point> points,
                  List<AnomalyView> anomalies) {
    }

    /**
     * A week of one channel.
     *
     * @param channel          the channel's name
     * @param messages         messages accepted
     * @param failed           messages failed
     * @param failureRate      failed as a share of messages
     * @param newRecipients    the sum over the week's hours of recipients new in that hour
     * @param peakHour         the busiest hour
     * @param peakHourMessages messages in the busiest hour
     * @param largestRequest   the biggest single request
     * @param topDestinations  the destinations with the most messages, most first
     * @param anomalies        anomalies raised
     * @param high             of which high severity
     * @param confirmed        decided as real
     * @param falseAlarms      decided as false alarms
     * @param open             not decided yet
     */
    record ChannelWeek(String channel, long messages, long failed, double failureRate, long newRecipients,
                       Instant peakHour, int peakHourMessages, int largestRequest, Map<String, Long> topDestinations,
                       long anomalies, long high, long confirmed, long falseAlarms, long open) {
    }

    /**
     * A tenant's week, Monday to Monday in UTC.
     *
     * @param clientId   the tenant
     * @param clientName its name
     * @param weekStart  the Monday the week starts on
     * @param from       the first hour, inclusive
     * @param to         the end, exclusive
     * @param channels   one entry per channel with traffic or anomalies, in channel order
     */
    record WeeklyReport(UUID clientId, String clientName, LocalDate weekStart, Instant from, Instant to,
                        List<ChannelWeek> channels) {
    }

    private final TrafficReader reader;
    private final AnomalyRepository anomalies;
    private final BaselineScorer scorer;
    private final Clock clock;
    private final int baselineWeeks;

    TrafficReports(TrafficReader reader, AnomalyRepository anomalies, BaselineScorer scorer, Clock clock, int baselineWeeks) {
        this.reader = reader;
        this.anomalies = anomalies;
        this.scorer = scorer;
        this.clock = clock;
        this.baselineWeeks = baselineWeeks;
    }

    /**
     * A tenant's hourly traffic on one channel up to the current hour.
     *
     * @param clientId the tenant
     * @param channel  the channel
     * @param days     how many days back
     * @return the series, or empty if there is no such tenant
     */
    Optional<Series> series(UUID clientId, Channel channel, int days) {
        Optional<String> name = anomalies.clientName(clientId);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Instant to = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant from = to.minus(Duration.ofDays(days));
        Optional<Instant> firstSeen = reader.firstSeen(clientId, channel.name());
        Map<Instant, TrafficHour> byHour = BaselineScorer.byHour(
                reader.range(clientId, channel.name(), from.minus(WEEK.multipliedBy(baselineWeeks)), to));
        List<Point> points = new ArrayList<>();
        for (Instant hour = from; hour.isBefore(to); hour = hour.plus(1, ChronoUnit.HOURS)) {
            TrafficHour measured = byHour.get(hour);
            Instant at = hour;
            OptionalDouble expected = firstSeen.map(first -> scorer.expectedMessages(at, first, byHour))
                    .orElse(OptionalDouble.empty());
            points.add(new Point(hour, measured == null ? 0 : measured.messages(), measured == null ? 0 : measured.failed(),
                    measured == null ? 0 : measured.newRecipients(),
                    expected.isPresent() ? expected.getAsDouble() : null));
        }
        return Optional.of(new Series(clientId, name.get(), channel.name(), from, to, points,
                anomalies.forClient(clientId, channel.name(), from, to)));
    }

    /**
     * A tenant's week.
     *
     * @param clientId  the tenant
     * @param weekStart the Monday the week starts on
     * @return the report, or empty if there is no such tenant
     * @throws IllegalArgumentException if {@code weekStart} is not a Monday
     */
    Optional<WeeklyReport> weekly(UUID clientId, LocalDate weekStart) {
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("week must be a Monday, not a " + weekStart.getDayOfWeek());
        }
        Optional<String> name = anomalies.clientName(clientId);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Instant from = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = from.plus(WEEK);
        Map<String, List<TrafficHour>> traffic = reader.range(clientId, null, from, to).stream()
                .collect(Collectors.groupingBy(TrafficHour::channel));
        Map<String, List<AnomalyView>> raised = anomalies.forClient(clientId, null, from, to).stream()
                .collect(Collectors.groupingBy(AnomalyView::channel));
        List<ChannelWeek> channels = Arrays.stream(Channel.values()).map(Channel::name)
                .filter(channel -> traffic.containsKey(channel) || raised.containsKey(channel))
                .map(channel -> channelWeek(channel, traffic.getOrDefault(channel, List.of()),
                        raised.getOrDefault(channel, List.of())))
                .toList();
        return Optional.of(new WeeklyReport(clientId, name.get(), weekStart, from, to, channels));
    }

    /** The Monday of the last full week before the current one, in UTC. */
    LocalDate lastCompleteWeek() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    private static ChannelWeek channelWeek(String channel, List<TrafficHour> hours, List<AnomalyView> raised) {
        long messages = hours.stream().mapToLong(TrafficHour::messages).sum();
        long failed = hours.stream().mapToLong(TrafficHour::failed).sum();
        Optional<TrafficHour> peak = hours.stream().max(Comparator.comparingInt(TrafficHour::messages));
        Map<String, Long> destinations = new HashMap<>();
        hours.forEach(hour -> hour.destinations().forEach((destination, count) -> destinations.merge(destination, (long) count, Long::sum)));
        Map<String, Long> top = destinations.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_DESTINATIONS)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first, LinkedHashMap::new));
        return new ChannelWeek(channel, messages, failed, messages == 0 ? 0 : (double) failed / messages,
                hours.stream().mapToLong(TrafficHour::newRecipients).sum(),
                peak.map(TrafficHour::hour).orElse(null), peak.map(TrafficHour::messages).orElse(0),
                hours.stream().mapToInt(TrafficHour::largestRequest).max().orElse(0), top,
                raised.size(), count(raised, anomaly -> anomaly.severity() == Severity.HIGH),
                count(raised, anomaly -> anomaly.status() == AnomalyStatus.CONFIRMED),
                count(raised, anomaly -> anomaly.status() == AnomalyStatus.FALSE_ALARM),
                count(raised, anomaly -> anomaly.status() == AnomalyStatus.OPEN));
    }

    private static long count(List<AnomalyView> raised, Predicate<AnomalyView> test) {
        return raised.stream().filter(test).count();
    }
}
