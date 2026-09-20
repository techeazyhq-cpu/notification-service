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

import com.techeazy.notification.application.TemplateRenderer;
import com.techeazy.notification.clientapi.Dtos.*;
import com.techeazy.notification.domain.Template;
import com.techeazy.notification.persistence.TemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Self-service templates. A client can create, edit and delete its own templates and can read (never change) the
 * shared ones. Requests take a snapshot of the content when they are accepted, so none of this affects messages
 * that are already queued or sent.
 */
@Service
public class ClientTemplateService {

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$");
    private static final String PREVIEW_RECIPIENT = "recipient@example.com";

    private final TemplateRepository templates;
    private final int maxPerClient;

    public ClientTemplateService(TemplateRepository templates,
                                 @Value("${client-api.max-templates-per-client:200}") int maxPerClient) {
        this.templates = templates;
        this.maxPerClient = maxPerClient;
    }

    @Transactional(readOnly = true)
    public List<TemplateView> list(AuthenticatedClient client) {
        return templates.findByClientIdOrClientIdIsNullOrderByNameAsc(client.id()).stream()
                .map(t -> view(t, client)).toList();
    }

    @Transactional(readOnly = true)
    public TemplateView get(AuthenticatedClient client, UUID id) {
        return view(visible(client, id), client);
    }

    @Transactional
    public TemplateView create(AuthenticatedClient client, TemplateInput in) {
        validate(client, in);
        requireNameAvailable(client, in.name());
        if (templates.countByClientId(client.id()) >= maxPerClient) {
            throw ApiException.conflict("TEMPLATE_LIMIT", "Template limit reached (" + maxPerClient + "); delete unused templates first");
        }
        Template t = new Template();
        t.setId(UUID.randomUUID());
        t.setClientId(client.id());
        t.setCreatedAt(Instant.now());
        apply(t, in);
        try {
            return view(templates.saveAndFlush(t), client);
        } catch (DataIntegrityViolationException e) { // lost a race with a concurrent create of the same name
            throw nameTaken(in.name());
        }
    }

    @Transactional
    public TemplateView update(AuthenticatedClient client, UUID id, TemplateInput in) {
        Template t = owned(client, id);
        validate(client, in);
        if (in.channel() != t.getChannel()) {
            throw ApiException.badRequest("The channel of a template cannot be changed; create a new template instead");
        }
        if (!in.name().equals(t.getName())) requireNameAvailable(client, in.name());
        apply(t, in);
        return view(t, client);
    }

    @Transactional
    public void delete(AuthenticatedClient client, UUID id) {
        templates.delete(owned(client, id)); // requests keep their content snapshot; template_id is set to NULL
    }

    /** Renders with the supplied values and leaves unresolved placeholders visible; nothing is stored or sent. */
    public PreviewView preview(PreviewRequest req) {
        Map<String, String> vars = new HashMap<>(req.variables() == null ? Map.of() : req.variables());
        vars.putIfAbsent(TemplateRenderer.RECIPIENT, PREVIEW_RECIPIENT);
        List<String> required = List.copyOf(TemplateRenderer.requiredVariables(req.subject(), req.body()));
        List<String> missing = required.stream().filter(v -> vars.get(v) == null).toList();
        return new PreviewView(TemplateRenderer.renderLenient(req.subject(), vars),
                TemplateRenderer.renderLenient(req.body(), vars), required, missing);
    }

    /** A name must not be held by a shared template (it would be ambiguous) nor by another of the client's templates. */
    private void requireNameAvailable(AuthenticatedClient client, String name) {
        if (templates.findByClientIdIsNullAndName(name).isPresent()) {
            throw ApiException.conflict("TEMPLATE_NAME_RESERVED", "'" + name + "' is the name of a shared template; pick another name");
        }
        if (templates.findByClientIdAndName(client.id(), name).isPresent()) throw nameTaken(name);
    }

    private static ApiException nameTaken(String name) {
        return ApiException.conflict("TEMPLATE_EXISTS", "You already have a template named '" + name + "'");
    }

    private void validate(AuthenticatedClient client, TemplateInput in) {
        if (!NAME.matcher(in.name()).matches()) {
            throw ApiException.badRequest("Name must be 2-64 characters: letters, digits, '.', '-' or '_', starting with a letter or digit");
        }
        if (!client.allows(in.channel())) {
            throw ApiException.forbidden("CHANNEL_NOT_ALLOWED", "Client is not allowed to use channel " + in.channel());
        }
        if (in.channel() == com.techeazy.notification.domain.Channel.EMAIL && (in.subject() == null || in.subject().isBlank())) {
            throw ApiException.badRequest("EMAIL templates need a subject");
        }
        if (TemplateRenderer.hasStrayBraces(in.subject()) || TemplateRenderer.hasStrayBraces(in.body())) {
            throw ApiException.badRequest("Malformed placeholder: use {{name}} with letters, digits, '.', '-' or '_' and matching braces");
        }
    }

    private static void apply(Template t, TemplateInput in) {
        t.setName(in.name());
        t.setChannel(in.channel());
        t.setSubject(in.subject() == null || in.subject().isBlank() ? null : in.subject());
        t.setBody(in.body());
        t.setUpdatedAt(Instant.now());
    }

    /** Own or shared; anything else looks like it does not exist. */
    private Template visible(AuthenticatedClient client, UUID id) {
        return templates.findById(id)
                .filter(t -> t.getClientId() == null || t.getClientId().equals(client.id()))
                .orElseThrow(() -> ApiException.notFound("Template " + id + " not found"));
    }

    private Template owned(AuthenticatedClient client, UUID id) {
        Template t = visible(client, id);
        if (t.getClientId() == null) {
            throw ApiException.forbidden("TEMPLATE_READ_ONLY", "Shared templates are managed by the platform administrators");
        }
        return t;
    }

    private static TemplateView view(Template t, AuthenticatedClient client) {
        boolean owned = client.id().equals(t.getClientId());
        return new TemplateView(t.getId(), t.getName(), t.getChannel(), t.getSubject(), t.getBody(),
                List.copyOf(TemplateRenderer.requiredVariables(t.getSubject(), t.getBody())),
                owned ? TemplateScope.OWNED : TemplateScope.SHARED, !owned, t.getCreatedAt(), t.getUpdatedAt());
    }
}
