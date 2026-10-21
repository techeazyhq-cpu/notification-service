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

package com.techeazy.notification.clientapi;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Writes each client's request for an AI template draft to the audit log of ADR-019, {@code admin_audit_event}, so
 * administrators see tenants' use of the model next to their own on the console's Audit log page (ADR-041). The
 * actor is {@code client:} followed by the client's name, and it has no admin role. As in ADR-019 the request body,
 * which carries the brief, is not stored, and a failure to write the entry is logged without failing the request.
 */
@Component
class DraftAuditTrail {

    static final String ACTOR_PREFIX = "client:";
    static final String ROUTE = "/v1/templates/drafts";

    private static final Logger LOG = LoggerFactory.getLogger(DraftAuditTrail.class);
    private static final int MAXIMUM_ACTOR_LENGTH = 100;
    private static final int MAXIMUM_PATH_LENGTH = 500;
    private static final int MAXIMUM_SOURCE_ADDRESS_LENGTH = 64;
    private static final int MAXIMUM_USER_AGENT_LENGTH = 300;

    private final JdbcClient jdbc;
    private final Clock clock;

    DraftAuditTrail(JdbcClient jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DraftAuditTrail(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void record(AuthenticatedClient client, HttpServletRequest request, int statusCode) {
        try {
            jdbc.sql("""
                    INSERT INTO admin_audit_event (id, occurred_at, actor, actor_role, http_method, route, path,
                                                   status_code, outcome, source_address, user_agent)
                    VALUES (:id, :occurredAt, :actor, NULL, :httpMethod, :route, :path, :statusCode, :outcome,
                            :sourceAddress, :userAgent)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("occurredAt", Timestamp.from(clock.instant()))
                    .param("actor", truncate(ACTOR_PREFIX + client.name(), MAXIMUM_ACTOR_LENGTH))
                    .param("httpMethod", request.getMethod())
                    .param("route", ROUTE)
                    .param("path", truncate(request.getRequestURI(), MAXIMUM_PATH_LENGTH))
                    .param("statusCode", statusCode)
                    .param("outcome", outcome(statusCode))
                    .param("sourceAddress", truncate(request.getRemoteAddr(), MAXIMUM_SOURCE_ADDRESS_LENGTH))
                    .param("userAgent", truncate(request.getHeader(HttpHeaders.USER_AGENT), MAXIMUM_USER_AGENT_LENGTH))
                    .update();
        } catch (RuntimeException failure) {
            LOG.error("Could not record the audit event for client {}'s template draft", client.id(), failure);
        }
    }

    /** The same classification as the admin audit trail's {@code AuditOutcome.forStatus}. */
    static String outcome(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return "DENIED";
        }
        if (statusCode >= 500) {
            return "FAILED";
        }
        return statusCode >= 400 ? "REJECTED" : "SUCCEEDED";
    }

    private static String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
