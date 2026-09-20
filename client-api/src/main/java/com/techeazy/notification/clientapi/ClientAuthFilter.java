package com.techeazy.notification.clientapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.application.RateLimitService;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.port.RateLimiter.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/** Authenticates {@code X-API-Key} and applies the client's API rate limit before any controller runs. */
@Component
public class ClientAuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_ATTRIBUTE = "notification.client";

    private final ClientRepository clients;
    private final RateLimitService rateLimits;
    private final ObjectMapper mapper;
    private final Cache<String, Optional<Client>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30)).maximumSize(10_000).build();

    public ClientAuthFilter(ClientRepository clients, RateLimitService rateLimits, ObjectMapper mapper) {
        this.clients = clients;
        this.rateLimits = rateLimits;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = req.getHeader("X-API-Key");
        if (key == null || key.isBlank()) {
            reject(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing X-API-Key header", null);
            return;
        }
        Optional<Client> client = cache.get(ApiKeys.hash(key), h -> clients.findByApiKeyHash(h));
        if (client.isEmpty() || !client.get().isActive()) {
            reject(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or disabled API key", null);
            return;
        }
        Decision d = rateLimits.checkClientApi(client.get().getId());
        if (!d.allowed()) {
            long seconds = Math.max(1, (d.waitMillis() + 999) / 1000);
            reject(res, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "API rate limit exceeded", seconds);
            return;
        }
        req.setAttribute(CLIENT_ATTRIBUTE, client.get());
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, HttpStatus status, String code, String message, Long retryAfter)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (retryAfter != null) res.setHeader("Retry-After", Long.toString(retryAfter));
        mapper.writeValue(res.getOutputStream(), new ApiExceptionHandler.ErrorBody(code, message));
    }
}
