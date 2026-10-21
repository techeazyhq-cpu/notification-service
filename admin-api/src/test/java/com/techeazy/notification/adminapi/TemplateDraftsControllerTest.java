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
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An administrator's brief reaches the drafting service as theirs, and the draft comes back unsaved. */
class TemplateDraftsControllerTest {

    private final TemplateDraftService drafting = mock(TemplateDraftService.class);
    private final TemplateDraftsController controller = new TemplateDraftsController(drafting);
    private final Authentication alice = new UsernamePasswordAuthenticationToken("alice", null, List.of());

    @Test
    void theBriefIsDraftedAsTheSignedInAdministrator() {
        DraftRequest expected = new DraftRequest(Channel.EMAIL, MessageCategory.TRANSACTIONAL, DraftTone.FORMAL,
                "Confirm a password change", List.of("firstName"));
        TemplateDraft draft = new TemplateDraft(Channel.EMAIL, "Your password was changed", "<p>Hi {{firstName}}</p>",
                List.of("firstName"), List.of(), null, "claude-opus-5-5");
        when(drafting.draft(DraftActor.administrator("alice"), expected)).thenReturn(draft);

        TemplateDraft returned = controller.draft(alice, new TemplateDraftsController.DraftInput(Channel.EMAIL,
                MessageCategory.TRANSACTIONAL, DraftTone.FORMAL, "Confirm a password change", List.of("firstName")));

        assertThat(returned).isEqualTo(draft);
    }

    @Test
    void theStatusIsTheAdministrators() {
        DraftingStatus status = new DraftingStatus(true, "claude-opus-5-5", 1000, 5);
        when(drafting.status(DraftActor.administrator("alice"))).thenReturn(status);

        assertThat(controller.status(alice)).isEqualTo(status);
        verify(drafting).status(DraftActor.administrator("alice"));
    }
}
