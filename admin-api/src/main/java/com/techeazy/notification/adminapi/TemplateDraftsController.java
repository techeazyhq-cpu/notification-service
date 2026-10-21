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
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.drafting.DraftActor;
import com.techeazy.notification.drafting.DraftRequest;
import com.techeazy.notification.drafting.DraftTone;
import com.techeazy.notification.drafting.DraftingStatus;
import com.techeazy.notification.drafting.TemplateDraft;
import com.techeazy.notification.drafting.TemplateDraftService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI drafting of shared templates (ADR-041). A draft is only returned to the console for the administrator to review
 * and edit; saving it goes through {@code POST /api/admin/templates} like any other template. Asking for a draft is a
 * {@code POST}, so the audit trail records every use (ADR-019); it needs the same role as changing a template.
 */
@RestController
@RequestMapping("/api/admin/templates")
class TemplateDraftsController {

    /** The brief: what the message is for, never recipient data or variable values. */
    record DraftInput(Channel channel, MessageCategory category, DraftTone tone, String purpose,
                      List<String> variables) {}

    private final TemplateDraftService drafting;

    TemplateDraftsController(TemplateDraftService drafting) {
        this.drafting = drafting;
    }

    @GetMapping("/drafting")
    DraftingStatus status(Authentication authentication) {
        return drafting.status(DraftActor.administrator(authentication.getName()));
    }

    @PostMapping("/drafts")
    TemplateDraft draft(Authentication authentication, @RequestBody DraftInput in) {
        return drafting.draft(DraftActor.administrator(authentication.getName()),
                new DraftRequest(in.channel(), in.category(), in.tone(), in.purpose(), in.variables()));
    }
}
