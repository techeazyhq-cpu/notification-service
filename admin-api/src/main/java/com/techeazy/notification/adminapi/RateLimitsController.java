package com.techeazy.notification.adminapi;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.RateLimitPolicy;
import com.techeazy.notification.domain.RateLimitScope;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.persistence.RateLimitPolicyRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Scopes: CLIENT_API needs clientId; CLIENT_CHANNEL needs clientId and channel; GLOBAL_CHANNEL needs channel.
 * Changes take effect on the running services within the policy cache TTL (10s by default).
 */
@RestController
@RequestMapping("/api/admin/rate-limits")
class RateLimitsController {

    record PolicyInput(@NotNull RateLimitScope scope, UUID clientId, Channel channel,
                       @NotNull @DecimalMin("0.001") BigDecimal ratePerSecond, @Min(1) int burst, boolean enabled) {}

    private final RateLimitPolicyRepository repo;
    private final ClientRepository clients;

    RateLimitsController(RateLimitPolicyRepository repo, ClientRepository clients) {
        this.repo = repo;
        this.clients = clients;
    }

    @GetMapping
    List<RateLimitPolicy> list() {
        return repo.findAll().stream().sorted(Comparator.comparing(RateLimitPolicy::getScope)
                .thenComparing(p -> String.valueOf(p.getChannel()))).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    RateLimitPolicy create(@Valid @RequestBody PolicyInput in) {
        RateLimitPolicy p = new RateLimitPolicy();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(Instant.now());
        apply(p, in);
        try {
            RateLimitPolicy saved = repo.saveAndFlush(p);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A policy for this scope/client/channel already exists");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    RateLimitPolicy update(@PathVariable UUID id, @Valid @RequestBody PolicyInput in) {
        RateLimitPolicy p = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        apply(p, in);
        return p;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        repo.deleteById(id);
    }

    private void apply(RateLimitPolicy p, PolicyInput in) {
        switch (in.scope()) {
            case CLIENT_API -> need(in.clientId() != null && in.channel() == null, "CLIENT_API needs clientId and no channel");
            case CLIENT_CHANNEL -> need(in.clientId() != null && in.channel() != null, "CLIENT_CHANNEL needs clientId and channel");
            case GLOBAL_CHANNEL -> need(in.clientId() == null && in.channel() != null, "GLOBAL_CHANNEL needs channel and no clientId");
        }
        if (in.clientId() != null && !clients.existsById(in.clientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown clientId");
        }
        p.setScope(in.scope());
        p.setClientId(in.clientId());
        p.setChannel(in.channel());
        p.setRatePerSecond(in.ratePerSecond());
        p.setBurst(in.burst());
        p.setEnabled(in.enabled());
        p.setUpdatedAt(Instant.now());
    }

    private static void need(boolean ok, String message) {
        if (!ok) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
