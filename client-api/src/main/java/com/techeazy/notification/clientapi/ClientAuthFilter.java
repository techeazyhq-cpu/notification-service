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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.clientapi.RequestSignatureGate.Admission;
import com.techeazy.notification.clientapi.RequestSignatureGate.SigningPolicy;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.error.ErrorBody;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.infra.ClientChangeBroadcast;
import com.techeazy.notification.error.TraceIdSource;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.port.RateLimiter.Decision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Authenticates {@code X-API-Key}, applies the client's API rate limit and checks the request's signature
 * ({@link RequestSignatureGate}, ADR-036) before any controller runs. A client's settings are cached for 30 seconds;
 * an administrator's change to a client empties the cache at once ({@link ClientChangeBroadcast}), and the expiry
 * only bounds how long an old setting can survive if that announcement is lost.
 */
@Component
public class ClientAuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_ATTRIBUTE = "notification.client";
    @SuppressWarnings("java:S1075")
    static final String SENDER_VERIFY_PATH = "/v1/senders/verify";

    private final ClientRepository clients;
    private final RateLimitService rateLimits;
    private final ObjectMapper mapper;
    private final TraceIdSource traceIds;
    private final Timer authTimer;
    private final Timer rateLimitTimer;
    /** The authenticated client together with its signing policy, which stays out of the request attribute. */
    private record Caller(AuthenticatedClient client, SigningPolicy signing) {
    }

    private final RequestSignatureGate signatures;
    private final Cache<String, Optional<Caller>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(10_000).build();

    public ClientAuthFilter(ClientRepository clients, RateLimitService rateLimits, ObjectMapper mapper,
                            MeterRegistry meters, TraceIdSource traceIds, RequestSignatureGate signatures) {
        this.signatures = signatures;
        this.traceIds = traceIds;
        this.clients = clients;
        this.rateLimits = rateLimits;
        this.mapper = mapper;
        this.authTimer = Timer.builder("notification.ingest.stage").tag("stage", "auth").publishPercentiles(0.5, 0.95, 0.99).register(meters);
        this.rateLimitTimer = Timer.builder("notification.ingest.stage").tag("stage", "rate_limit").publishPercentiles(0.5, 0.95, 0.99).register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/v1/") || uri.equals(SENDER_VERIFY_PATH)
                || uri.startsWith(ErrorCatalogueController.PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        if (key == null || key.isBlank()) {
            reject(res, ErrorCode.UNAUTHORIZED, "Missing X-API-Key header", null);
            return;
        }
        Optional<Caller> caller = authTimer.record(() -> cache.get(ApiKeys.hash(key), this::lookup));
        if (caller.isEmpty()) {
            reject(res, ErrorCode.UNAUTHORIZED, "Invalid or disabled API key", null);
            return;
        }
        AuthenticatedClient client = caller.get().client();
        Admission admission = signatures.admit(req, client.id(), caller.get().signing());
        if (!admission.admitted()) {
            reject(res, admission.refusal(), admission.reason(), null);
            return;
        }
        Decision d = rateLimitTimer.record(() -> rateLimits.checkClientApi(client.id()));
        if (!d.allowed()) {
            long seconds = Math.max(1, (d.waitMillis() + 999) / 1000);
            reject(res, ErrorCode.RATE_LIMITED, "API rate limit exceeded", seconds);
            return;
        }
        admission.request().setAttribute(CLIENT_ATTRIBUTE, client);
        chain.doFilter(admission.request(), res);
    }

    /**
     * Forgets every cached client, so the next request with any key reads the client again. Called when a client
     * changes; changes are rare, so dropping everything is simpler than tracking which key belongs to which client.
     */
    void forgetCachedClients() {
        cache.invalidateAll();
    }

    /** Only ACTIVE clients are cached as authenticated; a disabled client resolves to empty (401). */
    private Optional<Caller> lookup(String keyHash) {
        return clients.findByApiKeyHash(keyHash).filter(Client::isActive).map(c -> new Caller(AuthenticatedClient.from(c),
                new SigningPolicy(c.getSigningSecret(), c.isSigningRequired())));
    }

    private void reject(HttpServletResponse res, ErrorCode errorCode, String message, Long retryAfter)
            throws IOException {
        res.setStatus(errorCode.httpStatus().orElseThrow());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (retryAfter != null) res.setHeader("Retry-After", Long.toString(retryAfter));
        mapper.writeValue(res.getOutputStream(), ErrorBody.of(errorCode, message,
                traceIds.currentTraceId().orElse(null), ErrorCatalogueController.PATH));
    }
}
