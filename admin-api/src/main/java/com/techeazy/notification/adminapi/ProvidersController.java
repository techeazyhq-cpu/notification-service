package com.techeazy.notification.adminapi;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.persistence.ProviderConfigRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Provider configs. Secret-looking settings are masked on read; sending the mask back keeps the stored value. */
@RestController
@RequestMapping("/api/admin/providers")
class ProvidersController {

    static final String MASK = "********";
    private static final Set<String> SECRET_KEYS = Set.of("password", "authheader", "apikey", "token", "secret");

    record ProviderInput(@NotNull Channel channel, @NotBlank @Size(max = 120) String name, @NotNull ProviderType type,
                         Map<String, String> settings, boolean enabled, Integer priority) {}

    record ProviderView(UUID id, Channel channel, String name, ProviderType type, Map<String, String> settings,
                        boolean enabled, int priority, Instant updatedAt) {}

    private final ProviderConfigRepository repo;

    ProvidersController(ProviderConfigRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    List<ProviderView> list() {
        return repo.findAll().stream()
                .sorted(Comparator.comparing(ProviderConfig::getChannel).thenComparing(ProviderConfig::getPriority))
                .map(ProvidersController::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ProviderView create(@Valid @RequestBody ProviderInput in) {
        ProviderConfig p = new ProviderConfig();
        p.setId(UUID.randomUUID());
        p.setCreatedAt(Instant.now());
        apply(p, in);
        return view(repo.save(p));
    }

    @PutMapping("/{id}")
    @Transactional
    ProviderView update(@PathVariable UUID id, @Valid @RequestBody ProviderInput in) {
        ProviderConfig p = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        apply(p, in);
        return view(p);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        repo.deleteById(id);
    }

    private static void apply(ProviderConfig p, ProviderInput in) {
        Map<String, String> merged = new HashMap<>(in.settings() == null ? Map.of() : in.settings());
        merged.replaceAll((k, v) -> MASK.equals(v) ? p.getSettings().getOrDefault(k, "") : v);
        p.setChannel(in.channel());
        p.setName(in.name());
        p.setType(in.type());
        p.setSettings(merged);
        p.setEnabled(in.enabled());
        p.setPriority(in.priority() == null ? 100 : in.priority());
        p.setUpdatedAt(Instant.now());
    }

    private static ProviderView view(ProviderConfig p) {
        Map<String, String> masked = new HashMap<>(p.getSettings());
        masked.replaceAll((k, v) -> SECRET_KEYS.contains(k.toLowerCase()) && v != null && !v.isEmpty() ? MASK : v);
        return new ProviderView(p.getId(), p.getChannel(), p.getName(), p.getType(), masked, p.isEnabled(), p.getPriority(), p.getUpdatedAt());
    }
}
