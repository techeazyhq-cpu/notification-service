package com.techeazy.notification.adminapi;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Template;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/templates")
class TemplatesController {

    record TemplateInput(@NotBlank @Size(max = 120) String name, @NotNull Channel channel,
                         @Size(max = 500) String subject, @NotBlank String body) {}

    private final TemplateRepository repo;

    TemplatesController(TemplateRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    List<Template> list() {
        return repo.findAll().stream().sorted(Comparator.comparing(Template::getName)).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Template create(@Valid @RequestBody TemplateInput in) {
        Template t = new Template();
        t.setId(UUID.randomUUID());
        t.setCreatedAt(Instant.now());
        apply(t, in);
        return repo.save(t);
    }

    @PutMapping("/{id}")
    @Transactional
    Template update(@PathVariable UUID id, @Valid @RequestBody TemplateInput in) {
        Template t = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        apply(t, in);
        return t;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        try {
            repo.deleteById(id);
            repo.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Template is referenced by existing requests");
        }
    }

    private static void apply(Template t, TemplateInput in) {
        if (in.channel() == Channel.EMAIL && (in.subject() == null || in.subject().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMAIL templates need a subject");
        }
        t.setName(in.name());
        t.setChannel(in.channel());
        t.setSubject(in.subject());
        t.setBody(in.body());
        t.setUpdatedAt(Instant.now());
    }
}
