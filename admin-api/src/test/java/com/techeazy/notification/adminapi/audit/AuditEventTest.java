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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-01T10:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "200, SUCCEEDED", "201, SUCCEEDED", "204, SUCCEEDED", "302, SUCCEEDED",
            "401, DENIED", "403, DENIED",
            "400, REJECTED", "404, REJECTED", "409, REJECTED", "422, REJECTED", "429, REJECTED",
            "500, FAILED", "502, FAILED", "503, FAILED"
    })
    void theOutcomeFollowsTheResponseStatus(int statusCode, AuditOutcome expected) {
        assertThat(AuditOutcome.forStatus(statusCode)).isEqualTo(expected);
    }

    @Test
    void valuesLongerThanTheirColumnsAreCutSoAnOversizedRequestCannotLoseItsAuditRecord() {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), OCCURRED_AT, "a".repeat(500), AdminRole.ADMIN, "POST",
                "/api/admin/" + "r".repeat(500), "/api/admin/" + "p".repeat(900), 200, AuditOutcome.SUCCEEDED,
                "1".repeat(100), "u".repeat(1000));

        assertThat(event.actor()).hasSize(AuditEvent.MAXIMUM_ACTOR_LENGTH);
        assertThat(event.route()).hasSize(AuditEvent.MAXIMUM_ROUTE_LENGTH);
        assertThat(event.path()).hasSize(AuditEvent.MAXIMUM_PATH_LENGTH);
        assertThat(event.sourceAddress()).hasSize(AuditEvent.MAXIMUM_SOURCE_ADDRESS_LENGTH);
        assertThat(event.userAgent()).hasSize(AuditEvent.MAXIMUM_USER_AGENT_LENGTH);
    }

    @Test
    void absentOptionalValuesStayAbsent() {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), OCCURRED_AT, null, null, "DELETE", null,
                "/api/admin/clients/1", 401, AuditOutcome.DENIED, null, null);

        assertThat(event.actor()).isNull();
        assertThat(event.actorRole()).isNull();
        assertThat(event.route()).isNull();
        assertThat(event.sourceAddress()).isNull();
        assertThat(event.userAgent()).isNull();
    }
}
