package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.TemplateRenderer;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.domain.Template;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.persistence.TemplateRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Shared templates (visible to every client) are managed here. Templates owned by a client are listed, with their
 * owner, for support and moderation, but are read-only: they belong to that client.
 */
@RestController
@RequestMapping("/api/admin/templates")
class TemplatesController {

    record TemplateInput(@NotBlank @Size(max = 120) String name, @NotNull Channel channel,
                         @Size(max = 500) String subject, @NotBlank String body) {}

    /** {@code ownerClientId} is null for shared templates. */
    record TemplateView(UUID id, UUID ownerClientId, String ownerName, String name, Channel channel, String subject,
                        String body, Instant createdAt, Instant updatedAt) {}

    private final TemplateRepository repo;
    private final ClientRepository clients;

    TemplatesController(TemplateRepository repo, ClientRepository clients) {
        this.repo = repo;
        this.clients = clients;
    }

    @GetMapping
    List<TemplateView> list() {
        Map<UUID, String> owners = clients.findAll().stream().collect(Collectors.toMap(Client::getId, Client::getName));
        return repo.findAll().stream()
                .sorted(Comparator.comparing((Template t) -> t.getClientId() != null).thenComparing(Template::getName))
                .map(t -> view(t, owners)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    TemplateView create(@Valid @RequestBody TemplateInput in) {
        Template t = new Template();
        t.setId(UUID.randomUUID());
        t.setCreatedAt(Instant.now());
        apply(t, in);
        try {
            return view(repo.saveAndFlush(t), Map.of());
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A shared template named '" + in.name() + "' already exists");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    TemplateView update(@PathVariable UUID id, @Valid @RequestBody TemplateInput in) {
        Template t = shared(id);
        apply(t, in);
        return view(t, Map.of());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable UUID id) {
        repo.delete(shared(id)); // requests keep their content snapshot, so deleting never breaks history
    }

    private Template shared(UUID id) {
        Template t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (t.getClientId() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This template belongs to a client and is read-only here");
        }
        return t;
    }

    private static void apply(Template t, TemplateInput in) {
        if (in.channel() == Channel.EMAIL && (in.subject() == null || in.subject().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMAIL templates need a subject");
        }
        if (TemplateRenderer.hasStrayBraces(in.subject()) || TemplateRenderer.hasStrayBraces(in.body())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed placeholder: use {{name}} with matching braces");
        }
        t.setName(in.name());
        t.setChannel(in.channel());
        t.setSubject(in.subject());
        t.setBody(in.body());
        t.setUpdatedAt(Instant.now());
    }

    private static TemplateView view(Template t, Map<UUID, String> owners) {
        return new TemplateView(t.getId(), t.getClientId(), t.getClientId() == null ? null : owners.get(t.getClientId()),
                t.getName(), t.getChannel(), t.getSubject(), t.getBody(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
