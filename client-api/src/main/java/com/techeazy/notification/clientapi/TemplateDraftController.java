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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.drafting.DraftActor;
import com.techeazy.notification.drafting.DraftRequest;
import com.techeazy.notification.drafting.DraftTone;
import com.techeazy.notification.drafting.DraftingException;
import com.techeazy.notification.drafting.DraftingStatus;
import com.techeazy.notification.drafting.TemplateDraft;
import com.techeazy.notification.drafting.TemplateDraftService;
import com.techeazy.notification.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI drafting of a client's own templates (ADR-041). The draft is returned for the client to review and edit, then
 * saved with {@code POST /v1/templates} like any other template. Every request for a draft is written to the audit
 * log, whatever its outcome.
 */
@RestController
@RequestMapping("/v1/templates")
@Tag(name = "Templates")
public class TemplateDraftController {

    /** The brief: what the message is for. Never put recipient data or variable values in it. */
    public record DraftInput(Channel channel, MessageCategory category, DraftTone tone, String purpose,
                             List<String> variables) {}

    private final TemplateDraftService drafting;
    private final DraftAuditTrail audit;

    TemplateDraftController(TemplateDraftService drafting, DraftAuditTrail audit) {
        this.drafting = drafting;
        this.audit = audit;
    }

    @Operation(summary = "Whether AI template drafting is available to you, and how much of this month's budget is left")
    @GetMapping("/drafting")
    public DraftingStatus status(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client) {
        return drafting.status(actor(client));
    }

    @Operation(summary = "Draft a template with AI from a description",
            description = "Describe the message: its purpose, tone, the variables it needs and the channel (SMS, "
                    + "EMAIL or WHATSAPP). The draft meets the channel's rules and is not saved: review and edit it, "
                    + "then create it with POST /v1/templates. Only the description is sent to the model, so do not "
                    + "put recipient data or variable values in it. Rate limited, and limited by a monthly budget.")
    @PostMapping("/drafts")
    public TemplateDraft draft(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                               @RequestBody DraftInput in, HttpServletRequest request) {
        try {
            if (in.channel() != null && !client.allows(in.channel())) {
                throw ApiException.forbidden(ErrorCode.CHANNEL_NOT_ALLOWED, "Client is not allowed to use channel "
                        + in.channel());
            }
            TemplateDraft draft = drafting.draft(actor(client),
                    new DraftRequest(in.channel(), in.category(), in.tone(), in.purpose(), in.variables()));
            audit.record(client, request, 200);
            return draft;
        } catch (DraftingException refused) {
            audit.record(client, request, refused.reason().errorCode().httpStatus().orElse(500));
            throw refused;
        } catch (ApiException refused) {
            audit.record(client, request, refused.status().value());
            throw refused;
        } catch (RuntimeException failure) {
            audit.record(client, request, 500);
            throw failure;
        }
    }

    private static DraftActor actor(AuthenticatedClient client) {
        return DraftActor.client(client.id(), client.name());
    }
}
