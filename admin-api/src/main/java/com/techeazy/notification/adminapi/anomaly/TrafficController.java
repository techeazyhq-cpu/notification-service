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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

/** A tenant's traffic for the admin console (ADR-039): the hourly chart and the weekly report. Read-only. */
@RestController
@RequestMapping("/api/admin/traffic")
class TrafficController {

    private static final int MAXIMUM_DAYS = 90;

    private final TrafficReports reports;

    TrafficController(TrafficReports reports) {
        this.reports = reports;
    }

    /** Hourly traffic on one channel up to the current hour, with the expected baseline and the period's anomalies. */
    @GetMapping("/{clientId}")
    TrafficReports.Series series(@PathVariable UUID clientId, @RequestParam Channel channel,
                                 @RequestParam(defaultValue = "14") int days) {
        return reports.series(clientId, channel, Math.clamp(days, 1, MAXIMUM_DAYS)).orElseThrow(() -> notFound(clientId));
    }

    /** One week, Monday to Monday in UTC; the last full week unless {@code week} names its Monday. */
    @GetMapping("/{clientId}/weekly-report")
    TrafficReports.WeeklyReport weekly(@PathVariable UUID clientId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        return report(clientId, week);
    }

    /** The same report as CSV, for sharing outside the console. */
    @GetMapping(value = "/{clientId}/weekly-report/export", produces = "text/csv")
    ResponseEntity<String> export(@PathVariable UUID clientId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate week) {
        TrafficReports.WeeklyReport report = report(clientId, week);
        String filename = "traffic-" + clientId + "-" + report.weekStart() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(WeeklyReportCsv.render(report));
    }

    private TrafficReports.WeeklyReport report(UUID clientId, LocalDate week) {
        try {
            return reports.weekly(clientId, week == null ? reports.lastCompleteWeek() : week)
                    .orElseThrow(() -> notFound(clientId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    private static ResponseStatusException notFound(UUID clientId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No client " + clientId);
    }
}
