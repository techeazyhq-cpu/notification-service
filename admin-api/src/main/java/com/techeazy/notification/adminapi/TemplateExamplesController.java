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
import com.techeazy.notification.drafting.TemplateExample;
import com.techeazy.notification.drafting.TemplateExamples;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The library of example templates (ADR-046), for administrators to start shared templates from. It spends nothing,
 * so any administrator may read it; saving a template still needs {@code ADMIN}.
 */
@RestController
@RequestMapping("/api/admin/templates")
class TemplateExamplesController {

    private final TemplateExamples examples;

    TemplateExamplesController(TemplateExamples examples) {
        this.examples = examples;
    }

    @GetMapping("/examples")
    List<TemplateExample> examples(@RequestParam(required = false) Channel channel,
                                   @RequestParam(required = false) MessageCategory category) {
        return examples.find(channel, category);
    }
}
