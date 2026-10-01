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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditTrailFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final String ROTATE_KEY_ROUTE = "/api/admin/clients/{id}/rotate-key";
    private static final String ROTATE_KEY_PATH = "/api/admin/clients/7f0c/rotate-key";

    private final RecordingAuditLog auditLog = new RecordingAuditLog();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AuditTrailFilter filter = new AuditTrailFilter(auditLog, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("User-Agent", "admin-ui test");
        return request;
    }

    private static void signInAs(String username, AdminRole role) {
        var authorities = AuthorityUtils.createAuthorityList(Arrays.stream(AdminRole.values())
                .filter(granted -> granted.ordinal() <= role.ordinal())
                .map(granted -> "ROLE_" + granted.name())
                .toArray(String[]::new));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(username, null, authorities));
    }

    private static FilterChain controllerAnswering(int statusCode, String route) {
        return (request, response) -> {
            if (route != null) {
                request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
            }
            ((MockHttpServletResponse) response).setStatus(statusCode);
        };
    }

    @Test
    void aSuccessfulChangeIsRecordedWithWhoDidWhatToWhichResourceFromWhere() throws Exception {
        signInAs("alice", AdminRole.ADMIN);

        filter.doFilter(request("POST", ROTATE_KEY_PATH), new MockHttpServletResponse(),
                controllerAnswering(200, ROTATE_KEY_ROUTE));

        assertThat(auditLog.recorded()).singleElement().satisfies(event -> {
            assertThat(event.id()).isNotNull();
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.actor()).isEqualTo("alice");
            assertThat(event.actorRole()).isEqualTo(AdminRole.ADMIN);
            assertThat(event.httpMethod()).isEqualTo("POST");
            assertThat(event.route()).isEqualTo(ROTATE_KEY_ROUTE);
            assertThat(event.path()).isEqualTo(ROTATE_KEY_PATH);
            assertThat(event.statusCode()).isEqualTo(200);
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(event.sourceAddress()).isEqualTo("203.0.113.9");
            assertThat(event.userAgent()).isEqualTo("admin-ui test");
        });
    }

    @Test
    void theActorsRoleIsTheHighestRoleTheSessionCarries() throws Exception {
        signInAs("olivia", AdminRole.OPERATOR);

        filter.doFilter(request("POST", "/api/admin/dead-letters/reprocess"), new MockHttpServletResponse(),
                controllerAnswering(200, "/api/admin/dead-letters/reprocess"));

        assertThat(auditLog.recorded()).singleElement().extracting(AuditEvent::actorRole).isEqualTo(AdminRole.OPERATOR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void readsAreNotRecorded(String method) throws Exception {
        signInAs("alice", AdminRole.ADMIN);

        filter.doFilter(request(method, "/api/admin/clients"), new MockHttpServletResponse(),
                controllerAnswering(200, "/api/admin/clients"));

        assertThat(auditLog.recorded()).isEmpty();
    }

    @Test
    void requestsOutsideTheApiAreNotRecorded() throws Exception {
        filter.doFilter(request("POST", "/actuator/shutdown"), new MockHttpServletResponse(),
                controllerAnswering(404, null));

        assertThat(auditLog.recorded()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void everyStateChangingMethodIsRecorded(String method) throws Exception {
        signInAs("alice", AdminRole.ADMIN);

        filter.doFilter(request(method, "/api/admin/providers/3"), new MockHttpServletResponse(),
                controllerAnswering(204, "/api/admin/providers/{id}"));

        assertThat(auditLog.recorded()).singleElement().extracting(AuditEvent::httpMethod).isEqualTo(method);
    }

    @Test
    void aRefusedAttemptIsRecordedAsDeniedEvenThoughItNeverReachedAController() throws Exception {
        signInAs("victor", AdminRole.VIEWER);

        filter.doFilter(request("POST", ROTATE_KEY_PATH), new MockHttpServletResponse(),
                controllerAnswering(403, null));

        assertThat(auditLog.recorded()).singleElement().satisfies(event -> {
            assertThat(event.actor()).isEqualTo("victor");
            assertThat(event.actorRole()).isEqualTo(AdminRole.VIEWER);
            assertThat(event.route()).isNull();
            assertThat(event.path()).isEqualTo(ROTATE_KEY_PATH);
            assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
        });
    }

    @Test
    void anUnauthenticatedCallerIsRecordedWithoutAnActor() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        filter.doFilter(request("DELETE", "/api/admin/clients/1"), new MockHttpServletResponse(),
                controllerAnswering(401, null));

        assertThat(auditLog.recorded()).singleElement().satisfies(event -> {
            assertThat(event.actor()).isNull();
            assertThat(event.actorRole()).isNull();
            assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
        });
    }

    @Test
    void aSignInAttemptIsAttributedToTheUsernameItClaimed() throws Exception {
        MockHttpServletRequest login = request("POST", "/api/admin/auth/login");
        FilterChain rejectingLogin = (request, response) -> {
            request.setAttribute(AuditTrailFilter.CLAIMED_ACTOR_ATTRIBUTE, "mallory");
            controllerAnswering(401, "/api/admin/auth/login").doFilter(request, response);
        };

        filter.doFilter(login, new MockHttpServletResponse(), rejectingLogin);

        assertThat(auditLog.recorded()).singleElement().satisfies(event -> {
            assertThat(event.actor()).isEqualTo("mallory");
            assertThat(event.actorRole()).isNull();
            assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
        });
    }

    @Test
    void anAuthenticatedSessionWinsOverAClaimedUsername() throws Exception {
        signInAs("alice", AdminRole.ADMIN);
        FilterChain claimingSomeoneElse = (request, response) -> {
            request.setAttribute(AuditTrailFilter.CLAIMED_ACTOR_ATTRIBUTE, "bob");
            controllerAnswering(204, "/api/admin/auth/logout").doFilter(request, response);
        };

        filter.doFilter(request("POST", "/api/admin/auth/logout"), new MockHttpServletResponse(), claimingSomeoneElse);

        assertThat(auditLog.recorded()).singleElement().extracting(AuditEvent::actor).isEqualTo("alice");
    }

    @Test
    void anUnhandledFailureIsRecordedAsFailedAndStillPropagates() {
        signInAs("alice", AdminRole.ADMIN);
        FilterChain failing = (request, response) -> {
            throw new IllegalStateException("database unavailable");
        };
        MockHttpServletRequest create = request("POST", "/api/admin/clients");

        assertThatThrownBy(() -> filter.doFilter(create, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditLog.recorded()).singleElement().satisfies(event -> {
            assertThat(event.statusCode()).isEqualTo(500);
            assertThat(event.outcome()).isEqualTo(AuditOutcome.FAILED);
        });
    }

    @Test
    void anAuditStoreFailureNeitherBreaksTheResponseNorGoesUnnoticed() throws Exception {
        signInAs("alice", AdminRole.ADMIN);
        AuditTrailFilter filterWithBrokenStore = new AuditTrailFilter(new BrokenAuditLog(),
                Clock.fixed(NOW, ZoneOffset.UTC), meters);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithBrokenStore.doFilter(request("POST", "/api/admin/clients"), response,
                controllerAnswering(201, "/api/admin/clients"));

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(meters.get(AuditTrailFilter.WRITE_FAILURES_METRIC).counter().count()).isEqualTo(1.0);
    }

    private static final class BrokenAuditLog extends RecordingAuditLog {
        @Override
        public void record(AuditEvent event) {
            throw new IllegalStateException("audit table unavailable");
        }
    }
}
