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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Renders a weekly traffic report as CSV: a header block naming the tenant and week, then one row per channel. The
 * tenant's name is chosen by an administrator and could start a spreadsheet formula, so every cell that would is
 * prefixed with an apostrophe.
 */
final class WeeklyReportCsv {

    private static final String NEWLINE = "\r\n";

    private WeeklyReportCsv() {
    }

    static String render(TrafficReports.WeeklyReport report) {
        List<String> rows = new ArrayList<>();
        rows.add(row("Client", report.clientName()));
        rows.add(row("Client id", report.clientId().toString()));
        rows.add(row("Week", report.weekStart().toString()));
        rows.add("");
        rows.add(row("Channel", "Messages", "Failed", "Failure rate", "New recipients", "Peak hour", "Peak hour messages",
                "Largest request", "Top destinations", "Anomalies", "High", "Confirmed", "False alarms", "Open"));
        for (TrafficReports.ChannelWeek week : report.channels()) {
            rows.add(row(week.channel(), Long.toString(week.messages()), Long.toString(week.failed()),
                    String.format(Locale.ROOT, "%.4f", week.failureRate()), Long.toString(week.newRecipients()),
                    week.peakHour() == null ? "" : week.peakHour().toString(), Integer.toString(week.peakHourMessages()),
                    Integer.toString(week.largestRequest()), destinations(week),
                    Long.toString(week.anomalies()), Long.toString(week.high()), Long.toString(week.confirmed()),
                    Long.toString(week.falseAlarms()), Long.toString(week.open())));
        }
        return String.join(NEWLINE, rows) + NEWLINE;
    }

    private static String destinations(TrafficReports.ChannelWeek week) {
        return week.topDestinations().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(" "));
    }

    private static String row(String... cells) {
        return Arrays.stream(cells).map(WeeklyReportCsv::cell).collect(Collectors.joining(","));
    }

    private static String cell(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
