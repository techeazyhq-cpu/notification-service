/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */
package com.techeazy.notification.clientapi;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageCategory;
import com.techeazy.notification.drafting.TemplateExample;
import com.techeazy.notification.drafting.TemplateExamples;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The library of example templates (ADR-046): ready-made templates a client starts from, edits and saves with
 * {@code POST /v1/templates}. No model is involved, so it costs nothing and is there whether AI drafting is on or off.
 * Only the channels the client may use are listed.
 */
@RestController
@RequestMapping("/v1/templates")
@Tag(name = "Templates")
public class TemplateExamplesController {

    private final TemplateExamples examples;

    TemplateExamplesController(TemplateExamples examples) {
        this.examples = examples;
    }

    @Operation(summary = "Example templates to start from",
            description = "Ready-made templates for each channel and category, which meet the channel's rules as "
                    + "written. Copy one into your own template, change it as you need and create it with "
                    + "POST /v1/templates. Filter by channel, category and language (en, hi, ta or ar); only "
                    + "channels you may use are listed. Translations are in Hindi, Tamil and Arabic; their SMS "
                    + "text uses Unicode, so each SMS segment holds 70 characters.")
    @GetMapping("/examples")
    public List<TemplateExample> examples(
            @RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @RequestParam(required = false) Channel channel,
            @RequestParam(required = false) MessageCategory category,
            @RequestParam(required = false) String language) {
        return examples.find(channel, category, language).stream()
                .filter(example -> client.allows(example.channel())).toList();
    }
}
