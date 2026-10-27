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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyReportCsvTest {

    private static final UUID CLIENT = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void oneRowPerChannelAfterAHeaderBlock() {
        Map<String, Long> destinations = new LinkedHashMap<>();
        destinations.put("44", 390L);
        destinations.put("91", 10L);
        TrafficReports.ChannelWeek sms = new TrafficReports.ChannelWeek("SMS", 400, 10, 0.025, 7,
                Instant.parse("2026-08-25T12:00:00Z"), 300, 300, destinations, 2, 1, 0, 1, 1);

        String csv = WeeklyReportCsv.render(report("Acme, Inc.", List.of(sms)));

        assertThat(csv.split("\r\n")).containsExactly(
                "Client,\"Acme, Inc.\"",
                "Client id," + CLIENT,
                "Week,2026-08-24",
                "",
                "Channel,Messages,Failed,Failure rate,New recipients,Peak hour,Peak hour messages,Largest request,"
                        + "Top destinations,Anomalies,High,Confirmed,False alarms,Open",
                "SMS,400,10,0.0250,7,2026-08-25T12:00:00Z,300,300,44=390 91=10,2,1,0,1,1");
    }

    @Test
    void aNameThatWouldStartASpreadsheetFormulaIsNeutralised() {
        String csv = WeeklyReportCsv.render(report("=HYPERLINK(\"x\")", List.of()));

        assertThat(csv).startsWith("Client,\"'=HYPERLINK(\"\"x\"\")\"\r\n");
    }

    private static TrafficReports.WeeklyReport report(String name, List<TrafficReports.ChannelWeek> channels) {
        return new TrafficReports.WeeklyReport(CLIENT, name, LocalDate.parse("2026-08-24"),
                Instant.parse("2026-08-24T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"), channels);
    }
}
