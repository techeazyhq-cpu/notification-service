/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.ClientStatus;
import com.techeazy.notification.infra.ClientChangeBroadcast;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.persistence.ClientRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/clients")
class ClientsController {

    /** The longest an old signing secret can keep working after a rotation (ADR-051). */
    static final int MAX_PREVIOUS_SECRET_HOURS = 168;

    /**
     * @param signingEnabled              whether the client has a request-signing secret (ADR-036)
     * @param signingRequired             whether its state-changing requests must be signed
     * @param previousSecretAcceptedUntil until when the secret replaced by the last rotation is still accepted, or
     *                                    {@code null} when no grace period is running (ADR-051)
     */
    record ClientView(UUID id, String name, ClientStatus status, Set<Channel> allowedChannels, String apiKeyPrefix,
                      Instant createdAt, boolean signingEnabled, boolean signingRequired,
                      Instant previousSecretAcceptedUntil) {}

    /** @param previousSecretHours how long the secret being replaced keeps working; 0, the default, ends it at once */
    record SigningSecretRotation(@Min(0) @Max(MAX_PREVIOUS_SECRET_HOURS) int previousSecretHours) {
        static final SigningSecretRotation IMMEDIATE = new SigningSecretRotation(0);
    }

    record ClientInput(@NotBlank @Size(max = 120) String name, ClientStatus status, @NotEmpty Set<Channel> allowedChannels) {}

    /** The plaintext key is returned exactly once, here. */
    record ClientWithKey(ClientView client, String apiKey) {}

    /** The plaintext signing secret is returned exactly once, here. */
    record ClientWithSigningSecret(ClientView client, String signingSecret) {}

    record SigningRequirement(boolean required) {}

    private final ClientRepository repo;
    private final ClientSigningSecrets signingSecrets;
    private final ClientChangeBroadcast changes;
    private final Clock clock;

    ClientsController(ClientRepository repo, ClientSigningSecrets signingSecrets, ClientChangeBroadcast changes,
                      Clock clock) {
        this.repo = repo;
        this.signingSecrets = signingSecrets;
        this.changes = changes;
        this.clock = clock;
    }

    @GetMapping
    List<ClientView> list() {
        return repo.findAll().stream().sorted(Comparator.comparing(Client::getName)).map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ClientWithKey create(@Valid @RequestBody ClientInput in) {
        Client c = new Client();
        c.setId(UUID.randomUUID());
        c.setName(in.name());
        c.setStatus(in.status() == null ? ClientStatus.ACTIVE : in.status());
        c.setAllowedChannelSet(in.allowedChannels());
        String key = ApiKeys.generate();
        c.setApiKeyHash(ApiKeys.hash(key));
        c.setApiKeyPrefix(ApiKeys.displayPrefix(key));
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(c.getCreatedAt());
        return new ClientWithKey(view(repo.save(c)), key);
    }

    @PutMapping("/{id}")
    @Transactional
    ClientView update(@PathVariable UUID id, @Valid @RequestBody ClientInput in) {
        Client c = find(id);
        c.setName(in.name());
        if (in.status() != null) c.setStatus(in.status());
        c.setAllowedChannelSet(in.allowedChannels());
        c.setUpdatedAt(Instant.now());
        changes.clientChanged(id);
        return view(c);
    }

    @PostMapping("/{id}/rotate-key")
    @Transactional
    ClientWithKey rotate(@PathVariable UUID id) {
        Client c = find(id);
        String key = ApiKeys.generate();
        c.setApiKeyHash(ApiKeys.hash(key));
        c.setApiKeyPrefix(ApiKeys.displayPrefix(key));
        c.setUpdatedAt(Instant.now());
        changes.clientChanged(id);
        return new ClientWithKey(view(c), key);
    }

    /**
     * Issues a new request-signing secret. Without a grace period the old secret is refused from then on, so the client
     * must switch straight away. With one, the old secret keeps working until it ends, so the client can switch at
     * its own pace (ADR-051); a secret kept from an earlier rotation is dropped either way.
     */
    @PostMapping("/{id}/signing-secret")
    @Transactional
    ClientWithSigningSecret issueSigningSecret(@PathVariable UUID id,
                                               @Valid @RequestBody(required = false) SigningSecretRotation rotation) {
        Client c = find(id);
        int graceHours = (rotation == null ? SigningSecretRotation.IMMEDIATE : rotation).previousSecretHours();
        Instant now = clock.instant();
        boolean keepOld = graceHours > 0 && c.getSigningSecret() != null;
        c.setPreviousSigningSecret(keepOld ? c.getSigningSecret() : null);
        c.setPreviousSigningSecretExpiresAt(keepOld ? now.plus(Duration.ofHours(graceHours)) : null);
        String secret = signingSecrets.generate();
        c.setSigningSecret(signingSecrets.encryptForStorage(secret));
        c.setUpdatedAt(now);
        changes.clientChanged(id);
        return new ClientWithSigningSecret(view(c), secret);
    }

    /**
     * Removes the signing secret, and any previous one still in its grace period, which also stops requiring
     * signatures; the client then sends unsigned requests.
     */
    @DeleteMapping("/{id}/signing-secret")
    @Transactional
    ClientView removeSigningSecret(@PathVariable UUID id) {
        Client c = find(id);
        c.setSigningSecret(null);
        forgetPreviousSecret(c);
        c.setSigningRequired(false);
        c.setUpdatedAt(clock.instant());
        changes.clientChanged(id);
        return view(c);
    }

    /** Ends a rotation's grace period now, for example once the client has switched, or if the old secret leaked. */
    @DeleteMapping("/{id}/signing-secret/previous")
    @Transactional
    ClientView endPreviousSecretGracePeriod(@PathVariable UUID id) {
        Client c = find(id);
        forgetPreviousSecret(c);
        c.setUpdatedAt(clock.instant());
        changes.clientChanged(id);
        return view(c);
    }

    /** Makes signatures mandatory for the client's state-changing requests, or optional again. */
    @PutMapping("/{id}/signing-required")
    @Transactional
    ClientView requireSignatures(@PathVariable UUID id, @RequestBody SigningRequirement in) {
        Client c = find(id);
        if (in.required() && c.getSigningSecret() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Issue a signing secret before requiring signatures");
        }
        c.setSigningRequired(in.required());
        c.setUpdatedAt(clock.instant());
        changes.clientChanged(id);
        return view(c);
    }

    private Client find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));
    }

    private static void forgetPreviousSecret(Client c) {
        c.setPreviousSigningSecret(null);
        c.setPreviousSigningSecretExpiresAt(null);
    }

    private ClientView view(Client c) {
        Instant until = c.getPreviousSigningSecretExpiresAt();
        boolean graceRunning = c.getPreviousSigningSecret() != null && until != null && clock.instant().isBefore(until);
        return new ClientView(c.getId(), c.getName(), c.getStatus(), c.getAllowedChannelSet(), c.getApiKeyPrefix(),
                c.getCreatedAt(), c.getSigningSecret() != null, c.isSigningRequired(), graceRunning ? until : null);
    }
}
