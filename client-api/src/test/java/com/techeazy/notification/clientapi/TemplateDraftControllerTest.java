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
import com.techeazy.notification.drafting.DraftActor;
import com.techeazy.notification.drafting.DraftRequest;
import com.techeazy.notification.drafting.DraftingException;
import com.techeazy.notification.drafting.TemplateDraft;
import com.techeazy.notification.drafting.TemplateDraftService;
import com.techeazy.notification.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Every request for a draft is audited with how it ended; a channel the client may not use is refused first. */
class TemplateDraftControllerTest {

    private static final AuthenticatedClient ACME = new AuthenticatedClient(UUID.randomUUID(), "acme",
            Set.of(Channel.SMS, Channel.EMAIL));
    private static final TemplateDraftController.DraftInput SMS_BRIEF = new TemplateDraftController.DraftInput(
            Channel.SMS, null, null, "Tell the customer their parcel has shipped", List.of("trackingCode"));

    private final TemplateDraftService drafting = mock(TemplateDraftService.class);
    private final DraftAuditTrail audit = mock(DraftAuditTrail.class);
    private final TemplateDraftController controller = new TemplateDraftController(drafting, audit);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/templates/drafts");

    @Test
    void aDraftIsReturnedForTheClientAndAudited() {
        TemplateDraft draft = new TemplateDraft(Channel.SMS, null, "Shipped! Track it with {{trackingCode}}.",
                List.of("trackingCode"), List.of(), null, "claude-opus-5-5");
        when(drafting.draft(DraftActor.client(ACME.id(), "acme"), new DraftRequest(Channel.SMS, null, null,
                "Tell the customer their parcel has shipped", List.of("trackingCode")))).thenReturn(draft);

        assertThat(controller.draft(ACME, SMS_BRIEF, request)).isEqualTo(draft);
        verify(audit).record(ACME, request, 200);
    }

    @Test
    void aRefusedDraftIsAuditedWithItsStatus() {
        when(drafting.draft(any(), any())).thenThrow(new DraftingException(DraftingException.Reason.BUDGET_EXHAUSTED,
                "spent"));

        assertThatThrownBy(() -> controller.draft(ACME, SMS_BRIEF, request)).isInstanceOf(DraftingException.class);
        verify(audit).record(ACME, request, 429);
    }

    @Test
    void aChannelTheClientMayNotUseIsRefusedWithoutAskingTheModel() {
        TemplateDraftController.DraftInput whatsApp = new TemplateDraftController.DraftInput(Channel.WHATSAPP, null,
                null, "Tell the customer their parcel has shipped", List.of());

        assertThatThrownBy(() -> controller.draft(ACME, whatsApp, request))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CHANNEL_NOT_ALLOWED));
        verifyNoInteractions(drafting);
        verify(audit).record(ACME, request, 403);
    }

    @Test
    void anUnexpectedFailureIsAuditedAsAServerError() {
        when(drafting.draft(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> controller.draft(ACME, SMS_BRIEF, request)).isInstanceOf(IllegalStateException.class);
        verify(audit).record(ACME, request, 500);
    }

    @Test
    void auditOutcomesFollowTheAdminAuditTrailsClassification() {
        assertThat(DraftAuditTrail.outcome(200)).isEqualTo("SUCCEEDED");
        assertThat(DraftAuditTrail.outcome(403)).isEqualTo("DENIED");
        assertThat(DraftAuditTrail.outcome(429)).isEqualTo("REJECTED");
        assertThat(DraftAuditTrail.outcome(502)).isEqualTo("FAILED");
    }
}
