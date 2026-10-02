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
import com.techeazy.notification.domain.Template;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.persistence.TemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Editing a shared template from the console, which may not send a category, never turns a one-time-password
 * template into an ordinary one (ADR-033).
 */
class TemplatesControllerTest {

    private final TemplateRepository repo = mock(TemplateRepository.class);
    private final TemplatesController controller = new TemplatesController(repo, mock(ClientRepository.class));
    private final Template stored = new Template();

    @BeforeEach
    void setUp() {
        stored.setId(UUID.randomUUID());
        stored.setName("login-code");
        stored.setChannel(Channel.SMS);
        stored.setBody("Your code is {{code}}");
        stored.setCategory(MessageCategory.OTP);
        stored.setCreatedAt(Instant.now());
        stored.setUpdatedAt(Instant.now());
        when(repo.findById(stored.getId())).thenReturn(Optional.of(stored));
    }

    @Test
    void anUpdateWithoutACategoryKeepsTheTemplatesCategory() {
        TemplatesController.TemplateView view = controller.update(stored.getId(),
                new TemplatesController.TemplateInput("login-code", Channel.SMS, null, "Code: {{code}}", null));

        assertThat(view.category()).isEqualTo(MessageCategory.OTP);
        assertThat(view.body()).isEqualTo("Code: {{code}}");
    }

    @Test
    void anUpdateWithACategoryChangesIt() {
        TemplatesController.TemplateView view = controller.update(stored.getId(),
                new TemplatesController.TemplateInput("login-code", Channel.SMS, null, "Hello",
                        MessageCategory.PROMOTIONAL));

        assertThat(view.category()).isEqualTo(MessageCategory.PROMOTIONAL);
    }
}
