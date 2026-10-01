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
package com.techeazy.notification.adminapi.audit;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Lets administrators review the audit trail; restricted to the {@code ADMIN} role in the security configuration. */
@RestController
@RequestMapping("/api/admin/audit-events")
class AuditEventsController {

    private static final int MAXIMUM_PAGE_SIZE = 200;

    private final AuditLog auditLog;

    AuditEventsController(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /**
     * Audit events newest first. {@code from} is inclusive and {@code to} exclusive, both ISO-8601 instants such as
     * {@code 2026-10-01T00:00:00Z}.
     */
    @GetMapping
    AuditPage search(@RequestParam(required = false) String actor,
                     @RequestParam(required = false) AuditOutcome outcome,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                     @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "50") int size) {
        String actorOrAll = actor == null || actor.isBlank() ? null : actor.trim();
        return auditLog.search(new AuditQuery(actorOrAll, outcome, from, to), Math.max(page, 0),
                Math.clamp(size, 1, MAXIMUM_PAGE_SIZE));
    }
}
