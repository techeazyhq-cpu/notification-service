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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Records every state-changing call to the admin API in the {@link AuditLog}, after it has been handled, with how it
 * ended. It runs inside the security filter chain, right after the session is resolved, so it sees requests that
 * security refuses (401, 403) as well as those that reach an endpoint; new endpoints are covered without opting in.
 *
 * <p>Reads are not recorded: they change nothing and the admin UI polls them every few seconds. A sign-in has no
 * session yet, so the sign-in endpoint names the username it was given through {@link #CLAIMED_ACTOR_ATTRIBUTE}.
 *
 * <p>A failure to write the audit event does not undo or fail the admin action, which has already happened by then;
 * it is logged and counted in {@value #WRITE_FAILURES_METRIC} so it can be alerted on (see ADR-019).
 */
public class AuditTrailFilter extends OncePerRequestFilter {

    public static final String CLAIMED_ACTOR_ATTRIBUTE = AuditTrailFilter.class.getName() + ".claimedActor";
    public static final String WRITE_FAILURES_METRIC = "notification.audit.write_failures";

    private static final Logger LOG = LoggerFactory.getLogger(AuditTrailFilter.class);
    private static final String AUDITED_PATH_PREFIX = "/api/";
    private static final String ROLE_PREFIX = "ROLE_";
    private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AuditLog auditLog;
    private final Clock clock;
    private final Counter writeFailures;

    public AuditTrailFilter(AuditLog auditLog, Clock clock, MeterRegistry meters) {
        this.auditLog = auditLog;
        this.clock = clock;
        this.writeFailures = Counter.builder(WRITE_FAILURES_METRIC)
                .description("Admin actions whose audit event could not be stored")
                .register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return READ_ONLY_METHODS.contains(request.getMethod())
                || !request.getRequestURI().startsWith(AUDITED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            record(request, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            throw failure;
        }
        record(request, response.getStatus());
    }

    private void record(HttpServletRequest request, int statusCode) {
        Authentication session = signedInSession();
        AuditEvent event = new AuditEvent(UUID.randomUUID(), clock.instant(),
                session == null ? claimedActor(request) : session.getName(),
                session == null ? null : highestRole(session),
                request.getMethod(),
                (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                request.getRequestURI(),
                statusCode,
                AuditOutcome.forStatus(statusCode),
                request.getRemoteAddr(),
                request.getHeader(HttpHeaders.USER_AGENT));
        try {
            auditLog.record(event);
        } catch (RuntimeException failure) {
            writeFailures.increment();
            LOG.error("Could not record the audit event for {} {}", event.httpMethod(), event.path(), failure);
        }
    }

    private static Authentication signedInSession() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        return signedIn ? authentication : null;
    }

    private static String claimedActor(HttpServletRequest request) {
        return request.getAttribute(CLAIMED_ACTOR_ATTRIBUTE) instanceof String claimed ? claimed : null;
    }

    private static AdminRole highestRole(Authentication session) {
        Set<String> granted = session.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return Arrays.stream(AdminRole.values())
                .filter(role -> granted.contains(ROLE_PREFIX + role.name()))
                .reduce((lower, higher) -> higher)
                .orElse(null);
    }
}
