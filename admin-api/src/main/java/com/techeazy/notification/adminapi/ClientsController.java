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

package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.ClientStatus;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.persistence.ClientRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/clients")
class ClientsController {

    /**
     * @param signingEnabled  whether the client has a request-signing secret (ADR-036)
     * @param signingRequired whether its state-changing requests must be signed
     */
    record ClientView(UUID id, String name, ClientStatus status, Set<Channel> allowedChannels, String apiKeyPrefix,
                      Instant createdAt, boolean signingEnabled, boolean signingRequired) {}

    record ClientInput(@NotBlank @Size(max = 120) String name, ClientStatus status, @NotEmpty Set<Channel> allowedChannels) {}

    /** The plaintext key is returned exactly once, here. */
    record ClientWithKey(ClientView client, String apiKey) {}

    /** The plaintext signing secret is returned exactly once, here. */
    record ClientWithSigningSecret(ClientView client, String signingSecret) {}

    record SigningRequirement(boolean required) {}

    private final ClientRepository repo;
    private final ClientSigningSecrets signingSecrets;

    ClientsController(ClientRepository repo, ClientSigningSecrets signingSecrets) {
        this.repo = repo;
        this.signingSecrets = signingSecrets;
    }

    @GetMapping
    List<ClientView> list() {
        return repo.findAll().stream().sorted(Comparator.comparing(Client::getName)).map(ClientsController::view).toList();
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
        return new ClientWithKey(view(c), key);
    }

    /**
     * Issues a new request-signing secret, replacing any previous one at once: requests signed with the old secret
     * are refused from then on (within the client API's 30-second cache), so the client must switch straight away.
     */
    @PostMapping("/{id}/signing-secret")
    @Transactional
    ClientWithSigningSecret issueSigningSecret(@PathVariable UUID id) {
        Client c = find(id);
        String secret = signingSecrets.generate();
        c.setSigningSecret(signingSecrets.encryptForStorage(secret));
        c.setUpdatedAt(Instant.now());
        return new ClientWithSigningSecret(view(c), secret);
    }

    /** Removes the signing secret, which also stops requiring signatures; the client then sends unsigned requests. */
    @DeleteMapping("/{id}/signing-secret")
    @Transactional
    ClientView removeSigningSecret(@PathVariable UUID id) {
        Client c = find(id);
        c.setSigningSecret(null);
        c.setSigningRequired(false);
        c.setUpdatedAt(Instant.now());
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
        c.setUpdatedAt(Instant.now());
        return view(c);
    }

    private Client find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));
    }

    private static ClientView view(Client c) {
        return new ClientView(c.getId(), c.getName(), c.getStatus(), c.getAllowedChannelSet(), c.getApiKeyPrefix(),
                c.getCreatedAt(), c.getSigningSecret() != null, c.isSigningRequired());
    }
}
