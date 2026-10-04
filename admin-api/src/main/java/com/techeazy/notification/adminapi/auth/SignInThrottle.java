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

package com.techeazy.notification.adminapi.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.adminapi.ErrorCatalogueController;
import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.error.ErrorBody;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.error.TraceIdSource;
import com.techeazy.notification.port.RateLimiter.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limits administrator sign-in attempts per network address, shared across instances through the rate limiter, so
 * passwords cannot be guessed or sprayed across accounts at speed and one address cannot keep locking an
 * administrator out. The per-account lockout still applies on top. The address is the caller's own only when the
 * service sits behind a trusted proxy with {@code server.forward-headers-strategy} set; otherwise every caller behind
 * the proxy shares one budget.
 */
public class SignInThrottle extends OncePerRequestFilter {

    public static final String LOGIN_PATH = "/api/admin/auth/login";

    private final RateLimitService rateLimits;
    private final ObjectMapper json = new ObjectMapper();
    private final TraceIdSource traceIds;

    public SignInThrottle(RateLimitService rateLimits, TraceIdSource traceIds) {
        this.rateLimits = rateLimits;
        this.traceIds = traceIds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Decision decision = rateLimits.checkAdminSignIn(request.getRemoteAddr());
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(ErrorCode.RATE_LIMITED.httpStatus().orElseThrow());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (decision.waitMillis() + 999) / 1000)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ErrorBody.of(ErrorCode.RATE_LIMITED,
                "Too many sign-in attempts from this address; wait and try again",
                traceIds.currentTraceId().orElse(null), ErrorCatalogueController.PATH));
    }
}
