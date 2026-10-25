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

package com.techeazy.notification.drafting;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;

import java.util.List;

/**
 * What the author wants a template for. Only this description goes to the model: never recipients, never variable
 * values, never existing templates or messages (ADR-041).
 *
 * @param channel   the channel the template is for; SMS, EMAIL, WHATSAPP or PUSH
 * @param category  what the messages are for; absent means {@link MessageCategory#TRANSACTIONAL}
 * @param tone      the voice of the draft; absent means {@link DraftTone#PROFESSIONAL}
 * @param purpose   the author's description of the message, in their own words
 * @param variables the names of the placeholders the template may use, without braces
 */
public record DraftRequest(Channel channel, MessageCategory category, DraftTone tone, String purpose,
                           List<String> variables) {

    public DraftRequest {
        category = category == null ? MessageCategory.TRANSACTIONAL : category;
        tone = tone == null ? DraftTone.PROFESSIONAL : tone;
        purpose = purpose == null ? null : purpose.strip();
        variables = variables == null ? List.of() : variables.stream().map(v -> v == null ? null : v.strip()).toList();
    }
}
