package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.ApiKeys;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.ClientStatus;
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

    record ClientView(UUID id, String name, ClientStatus status, Set<Channel> allowedChannels, String apiKeyPrefix,
                      Instant createdAt) {}

    record ClientInput(@NotBlank @Size(max = 120) String name, ClientStatus status, @NotEmpty Set<Channel> allowedChannels) {}

    /** The plaintext key is returned exactly once, here. */
    record ClientWithKey(ClientView client, String apiKey) {}

    private final ClientRepository repo;

    ClientsController(ClientRepository repo) {
        this.repo = repo;
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

    private Client find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Client not found"));
    }

    private static ClientView view(Client c) {
        return new ClientView(c.getId(), c.getName(), c.getStatus(), c.getAllowedChannelSet(), c.getApiKeyPrefix(), c.getCreatedAt());
    }
}
