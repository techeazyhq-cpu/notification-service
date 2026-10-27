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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.UUID;

/**
 * Lets administrators review traffic anomalies and decide them (ADR-039). Reading needs {@code VIEWER}; deciding needs
 * {@code OPERATOR}, like the other day-to-day recovery actions, and is recorded in the audit log like every change.
 */
@RestController
@RequestMapping("/api/admin/anomalies")
class AnomaliesController {

    private static final int MAXIMUM_PAGE_SIZE = 200;
    private static final int MAXIMUM_NOTE_LENGTH = 500;

    /**
     * An administrator's decision on an anomaly.
     *
     * @param status {@code CONFIRMED} or {@code FALSE_ALARM}
     * @param note   why, optional, at most 500 characters
     */
    record Resolution(AnomalyStatus status, String note) {
    }

    private final AnomalyRepository anomalies;
    private final Clock clock;

    AnomaliesController(AnomalyRepository anomalies, Clock clock) {
        this.anomalies = anomalies;
        this.clock = clock;
    }

    /** Anomalies newest hour first, optionally narrowed by status, tenant, channel and severity. */
    @GetMapping
    AnomalyRepository.Page search(@RequestParam(required = false) AnomalyStatus status,
                                  @RequestParam(required = false) UUID clientId,
                                  @RequestParam(required = false) Channel channel,
                                  @RequestParam(required = false) Severity severity,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        AnomalyRepository.Query query = new AnomalyRepository.Query(status, clientId,
                channel == null ? null : channel.name(), severity);
        return anomalies.search(query, Math.max(page, 0), Math.clamp(size, 1, MAXIMUM_PAGE_SIZE));
    }

    @GetMapping("/{id}")
    AnomalyView get(@PathVariable UUID id) {
        return anomalies.find(id).orElseThrow(() -> notFound(id));
    }

    /** Records whether an anomaly was real. A decision can be changed, but an anomaly cannot be reopened. */
    @PostMapping("/{id}/status")
    AnomalyView resolve(@PathVariable UUID id, @RequestBody Resolution resolution, Authentication authentication) {
        if (resolution.status() == null || resolution.status() == AnomalyStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be CONFIRMED or FALSE_ALARM");
        }
        String note = resolution.note() == null || resolution.note().isBlank() ? null : resolution.note().strip();
        if (note != null && note.length() > MAXIMUM_NOTE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "note must be at most " + MAXIMUM_NOTE_LENGTH + " characters");
        }
        return anomalies.resolve(id, resolution.status(), authentication.getName(), note, clock.instant())
                .orElseThrow(() -> notFound(id));
    }

    private static ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No anomaly " + id);
    }
}
