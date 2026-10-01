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

import com.techeazy.notification.adminapi.auth.AdminRole;

import java.time.Instant;
import java.util.UUID;

/**
 * One state-changing admin API call: who made it, in which role, what they asked for, how it ended, and from where.
 * Request bodies are deliberately not part of it, because they carry passwords, provider credentials and recipient
 * addresses. Text values are cut to their column sizes here, so an oversized header or path can never cost the record.
 *
 * @param actor     the signed-in administrator, or for a sign-in the username claimed; absent when unknown
 * @param actorRole the role the session carried; absent when nobody was signed in
 * @param route     the endpoint's URI template, such as {@code /api/admin/clients/{id}/rotate-key}; absent when the
 *                  request was refused before it reached an endpoint
 * @param path      the URI actually requested, without its query string
 */
@SuppressWarnings("java:S107")
public record AuditEvent(UUID id, Instant occurredAt, String actor, AdminRole actorRole, String httpMethod,
                         String route, String path, int statusCode, AuditOutcome outcome, String sourceAddress,
                         String userAgent) {

    public static final int MAXIMUM_ACTOR_LENGTH = 100;
    public static final int MAXIMUM_ROUTE_LENGTH = 300;
    public static final int MAXIMUM_PATH_LENGTH = 500;
    public static final int MAXIMUM_SOURCE_ADDRESS_LENGTH = 64;
    public static final int MAXIMUM_USER_AGENT_LENGTH = 300;

    public AuditEvent {
        actor = truncate(actor, MAXIMUM_ACTOR_LENGTH);
        route = truncate(route, MAXIMUM_ROUTE_LENGTH);
        path = truncate(path, MAXIMUM_PATH_LENGTH);
        sourceAddress = truncate(sourceAddress, MAXIMUM_SOURCE_ADDRESS_LENGTH);
        userAgent = truncate(userAgent, MAXIMUM_USER_AGENT_LENGTH);
    }

    private static String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
