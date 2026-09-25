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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.infra.ProviderSecrets;
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
import java.util.UUID;

/** Provider configs. Secret-looking settings are masked on read; sending the mask back keeps the stored value. */
@RestController
@RequestMapping("/api/admin/providers")
class ProvidersController {

    static final String MASK = "********";

    record ProviderInput(@NotNull Channel channel, @NotBlank @Size(max = 120) String name, @NotNull ProviderType type,
                         Map<String, String> settings, boolean enabled, Integer priority) {}

    record ProviderView(UUID id, Channel channel, String name, ProviderType type, Map<String, String> settings,
                        boolean enabled, int priority, Instant updatedAt) {}

    private final ProviderConfigRepository repo;
    private final ProviderSecrets secrets;

    ProvidersController(ProviderConfigRepository repo, ProviderSecrets secrets) {
        this.repo = repo;
        this.secrets = secrets;
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

    private void apply(ProviderConfig p, ProviderInput in) {
        Map<String, String> merged = new HashMap<>(in.settings() == null ? Map.of() : in.settings());
        merged.replaceAll((k, v) -> MASK.equals(v) ? p.getSettings().getOrDefault(k, "") : v);
        p.setChannel(in.channel());
        p.setName(in.name());
        p.setType(in.type());
        p.setSettings(secrets.encryptForStorage(merged));
        p.setEnabled(in.enabled());
        p.setPriority(in.priority() == null ? 100 : in.priority());
        p.setUpdatedAt(Instant.now());
    }

    private static ProviderView view(ProviderConfig p) {
        Map<String, String> masked = new HashMap<>(p.getSettings());
        masked.replaceAll((k, v) -> ProviderSecrets.SECRET_KEYS.contains(k.toLowerCase()) && v != null && !v.isEmpty() ? MASK : v);
        return new ProviderView(p.getId(), p.getChannel(), p.getName(), p.getType(), masked, p.isEnabled(), p.getPriority(), p.getUpdatedAt());
    }
}
