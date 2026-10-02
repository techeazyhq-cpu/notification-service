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
package com.techeazy.notification.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Guards an error dictionary served without credentials (ADR-031). No API key or session means no per-client limit
 * applies, so the dictionary has a shared rate limit of its own, and its answers are marked cacheable for an hour:
 * the entries change only with a release. Each API registers one for the path it serves the dictionary at.
 */
public class ErrorCatalogueThrottle extends OncePerRequestFilter {

    static final String CACHE_CONTROL = "public, max-age=3600";

    private final RateLimitService rateLimits;
    private final ObjectMapper mapper;
    private final TraceIdSource traceIds;
    private final String cataloguePath;

    public ErrorCatalogueThrottle(RateLimitService rateLimits, ObjectMapper mapper, TraceIdSource traceIds,
                                  String cataloguePath) {
        this.rateLimits = rateLimits;
        this.mapper = mapper;
        this.traceIds = traceIds;
        this.cataloguePath = cataloguePath;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.equals(cataloguePath) && !uri.startsWith(cataloguePath + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Decision decision = rateLimits.checkPublicCatalogue(cataloguePath);
        if (!decision.allowed()) {
            response.setStatus(ErrorCode.RATE_LIMITED.httpStatus().orElseThrow());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (decision.waitMillis() + 999) / 1000)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ErrorBody.of(ErrorCode.RATE_LIMITED,
                    "Too many requests for the error dictionary; it is also published as docs/error-codes.md",
                    traceIds.currentTraceId().orElse(null), cataloguePath));
            return;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        chain.doFilter(request, response);
    }
}
