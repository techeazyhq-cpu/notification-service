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
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.persistence.ClientRepository;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.application.OutboxPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The message list tells an operator what a message is and why it failed (ADR-031, ADR-033). */
class MessagesControllerTest {

    private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    private final MessagesController controller = new MessagesController(messages, mock(ClientRepository.class),
            mock(OutboxPublisher.class), mock(MessageRetry.class));

    @Test
    @SuppressWarnings("unchecked")
    void aRowCarriesTheCategoryTheExpiryAndTheErrorIdOfAnExpiredOneTimePassword() {
        Instant expiresAt = Instant.parse("2026-10-02T12:05:00Z");
        NotificationMessage expired = new NotificationMessage();
        expired.setId(UUID.randomUUID());
        expired.setClientId(UUID.randomUUID());
        expired.setChannel(Channel.SMS);
        expired.setStatus(MessageStatus.FAILED);
        expired.setCategory(MessageCategory.OTP);
        expired.setExpiresAt(expiresAt);
        expired.setErrorCode(ErrorCode.OTP_EXPIRED);
        when(messages.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(expired)));

        MessagesController.Row row = controller.search(null, null, null, null, null, 0, 50).items().getFirst();

        assertThat(row.category()).isEqualTo(MessageCategory.OTP);
        assertThat(row.expiresAt()).isEqualTo(expiresAt);
        assertThat(row.errorCode()).isEqualTo(ErrorCode.OTP_EXPIRED);
        assertThat(row.errorId()).isEqualTo(ErrorCode.OTP_EXPIRED.errorId());
    }
}
