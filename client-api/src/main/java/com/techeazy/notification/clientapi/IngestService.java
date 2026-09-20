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

import com.techeazy.notification.application.OutboxPublisher;
import com.techeazy.notification.application.TemplateRenderer;
import com.techeazy.notification.billing.application.Admission;
import com.techeazy.notification.billing.application.AdmissionControl;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.clientapi.Dtos.SubmitResponse;
import com.techeazy.notification.clientapi.IngestPersister.Recipient;
import com.techeazy.notification.domain.*;
import com.techeazy.notification.persistence.NotificationRequestRepository;
import com.techeazy.notification.persistence.TemplateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Accept path: validate, admit against the client's billing account, persist as PENDING in one transaction, publish
 * to Pulsar, flip to QUEUED. The API answers 202 once the request is durable; delivery outcome is read via the status API.
 *
 * <p>Billing admission runs inside the storing transaction, so a refused or failed request leaves no charge behind.
 * The content of the request is a snapshot taken here, so later template edits or deletes never touch it. Messages
 * that fail to publish stay PENDING and the outbox sweeper retries them. A repeated Idempotency-Key returns the
 * original request instead of creating or charging a second one, including when two submissions race.
 */
@Service
public class IngestService {

    private final TemplateCache templates;
    private final NotificationRequestRepository requests;
    private final IngestPersister persister;
    private final OutboxPublisher outbox;
    private final StatusQueryService status;
    private final AdmissionControl admission;
    private final SenderService senders;
    private final int maxBulkRecipients;
    private final MeterRegistry meters;
    private final Map<String, Timer> stageTimers = new ConcurrentHashMap<>();

    public IngestService(TemplateCache templates, NotificationRequestRepository requests, IngestPersister persister,
                         OutboxPublisher outbox, StatusQueryService status, AdmissionControl admission,
                         SenderService senders, MeterRegistry meters,
                         @Value("${client-api.max-bulk-recipients:50000}") int maxBulkRecipients) {
        this.templates = templates;
        this.requests = requests;
        this.persister = persister;
        this.outbox = outbox;
        this.status = status;
        this.admission = admission;
        this.senders = senders;
        this.meters = meters;
        this.maxBulkRecipients = maxBulkRecipients;
    }

    public int maxBulkRecipients() {
        return maxBulkRecipients;
    }

    /** Everything a caller supplies for one submission; {@code idempotencyKey}, {@code from} and content fields may be null. */
    public record SubmitCommand(RequestKind kind, Channel channel, String templateName, String subject, String body,
                                List<Recipient> recipients, String clientReference, String idempotencyKey, String from) {

        public SubmitCommand(RequestKind kind, Channel channel, String templateName, String subject, String body,
                             List<Recipient> recipients, String clientReference, String idempotencyKey) {
            this(kind, channel, templateName, subject, body, recipients, clientReference, idempotencyKey, null);
        }
    }

    public SubmitResponse submit(AuthenticatedClient client, SubmitCommand cmd) {
        requireChannelAllowed(client, cmd.channel());
        requireWithinBulkLimit(cmd.recipients());
        Content content = timed("content", () -> resolveContent(client, cmd.channel(), cmd.templateName(), cmd.subject(), cmd.body()));
        validateRecipients(cmd.channel(), cmd.recipients());
        validateVariables(content, cmd.recipients());
        Optional<SenderAddress> sender = timed("sender", () -> senders.resolve(client, cmd.channel(), cmd.from()));

        Optional<NotificationRequest> duplicate = findByIdempotencyKey(client, cmd.idempotencyKey());
        if (duplicate.isPresent()) {
            return replay(duplicate.get());
        }

        NotificationRequest request = newRequest(client, cmd, content);
        sender.ifPresent(s -> {
            request.setSenderEmail(s.email());
            request.setSenderName(s.displayName());
        });
        List<NotificationMessage> messages;
        try {
            messages = timed("persist", () -> persister.persist(request, cmd.recipients(), () -> admit(client, cmd, request)));
        } catch (DataIntegrityViolationException e) {
            return findByIdempotencyKey(client, cmd.idempotencyKey()).map(this::replay).orElseThrow(() -> e);
        }
        timed("publish", () -> outbox.publishAndMarkQueuedLater(messages));
        return accepted(request, messages);
    }

    private <T> T timed(String stage, Supplier<T> work) {
        Timer.Sample sample = Timer.start(meters);
        try {
            return work.get();
        } finally {
            sample.stop(stageTimers.computeIfAbsent(stage, s -> Timer.builder("notification.ingest.stage").tag("stage", s)
                    .publishPercentiles(0.5, 0.95, 0.99).register(meters)));
        }
    }

    private void requireChannelAllowed(AuthenticatedClient client, Channel channel) {
        if (!client.allows(channel)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHANNEL_NOT_ALLOWED", "Client is not allowed to use channel " + channel);
        }
    }

    private void requireWithinBulkLimit(List<Recipient> recipients) {
        if (recipients.size() > maxBulkRecipients) {
            throw ApiException.badRequest("At most " + maxBulkRecipients + " recipients per request");
        }
    }

    private Optional<NotificationRequest> findByIdempotencyKey(AuthenticatedClient client, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return requests.findByClientIdAndIdempotencyKey(client.id(), idempotencyKey);
    }

    private NotificationRequest newRequest(AuthenticatedClient client, SubmitCommand cmd, Content content) {
        NotificationRequest request = new NotificationRequest();
        request.setId(UUID.randomUUID());
        request.setClientId(client.id());
        request.setKind(cmd.kind());
        request.setChannel(cmd.channel());
        request.setTemplateId(content.templateId());
        request.setSubject(content.subject());
        request.setBody(content.body());
        request.setTotal(cmd.recipients().size());
        request.setIdempotencyKey(cmd.idempotencyKey());
        request.setClientReference(cmd.clientReference());
        request.setCreatedAt(Instant.now());
        return request;
    }

    private void admit(AuthenticatedClient client, SubmitCommand cmd, NotificationRequest request) {
        admission.admit(new Admission(client.id(), cmd.channel(), cmd.recipients().size(), HoldScope.REQUEST, request.getId()));
    }

    private SubmitResponse accepted(NotificationRequest request, List<NotificationMessage> messages) {
        List<UUID> ids = request.getKind() == RequestKind.SINGLE ? messages.stream().map(NotificationMessage::getId).toList() : null;
        return new SubmitResponse(request.getId(), request.getKind(), RequestStatus.PROCESSING, request.getTotal(), ids, false,
                request.getCreatedAt());
    }

    private SubmitResponse replay(NotificationRequest existing) {
        var view = status.view(existing);
        return new SubmitResponse(existing.getId(), existing.getKind(), view.status(), existing.getTotal(), null, true, existing.getCreatedAt());
    }

    /** The content a request will be sent with, and the template it came from (null for inline content). */
    record Content(UUID templateId, String subject, String body) {}

    /** The client's own template wins over a shared one of the same name. */
    private Content resolveContent(AuthenticatedClient client, Channel channel, String templateName, String subject, String body) {
        if (templateName != null && !templateName.isBlank()) {
            TemplateCache.TemplateContent t = templates.find(client.id(), templateName)
                    .orElseThrow(() -> ApiException.badRequest("Unknown template '" + templateName + "'"));
            if (t.channel() != channel) {
                throw ApiException.badRequest("Template '" + templateName + "' is for channel " + t.channel() + ", not " + channel);
            }
            return new Content(t.id(), t.subject(), t.body());
        }
        if (body == null || body.isBlank()) throw ApiException.badRequest("Provide either templateName or body");
        if (channel == Channel.EMAIL && (subject == null || subject.isBlank())) {
            throw ApiException.badRequest("subject is required for inline EMAIL content");
        }
        return new Content(null, subject, body);
    }

    /**
     * Fails the whole request up front if any recipient lacks a variable the content uses, instead of letting those
     * messages fail one by one later. {{recipient}} is built in.
     */
    private void validateVariables(Content content, List<Recipient> recipients) {
        Set<String> required = TemplateRenderer.requiredVariables(content.subject(), content.body());
        if (required.isEmpty()) return;
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < recipients.size() && problems.size() < 10; i++) {
            Map<String, String> vars = recipients.get(i).variables();
            List<String> missing = required.stream().filter(v -> vars == null || vars.get(v) == null).toList();
            if (!missing.isEmpty()) problems.add("recipient #" + (i + 1) + " is missing " + String.join(", ", missing));
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Missing template variables (first " + problems.size() + "): " + String.join("; ", problems));
        }
    }

    private void validateRecipients(Channel channel, List<Recipient> recipients) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < recipients.size() && problems.size() < 10; i++) {
            String reason = RecipientValidator.check(channel, recipients.get(i).address());
            if (reason != null) problems.add("recipient #" + (i + 1) + ": " + reason);
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Invalid recipients (first " + problems.size() + "): " + String.join("; ", problems));
        }
    }
}
