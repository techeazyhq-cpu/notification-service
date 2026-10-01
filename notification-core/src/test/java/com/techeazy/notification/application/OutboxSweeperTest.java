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

package com.techeazy.notification.application;

import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxSweeperTest {

    private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    private final OutboxPublisher outbox = mock(OutboxPublisher.class);
    private final OutboxSweeper sweeper = new OutboxSweeper(messages, outbox, new NotificationProperties());

    private static NotificationMessage message(MessageStatus status) {
        NotificationMessage m = new NotificationMessage();
        m.setId(UUID.randomUUID());
        m.setStatus(status);
        return m;
    }

    @Test
    void republishesMessagesThatStayedQueuedAndKeepsThemQueued() {
        NotificationMessage lost = message(MessageStatus.QUEUED);
        when(messages.lockStale(eq("QUEUED"), any(), anyInt())).thenReturn(List.of(lost));
        when(outbox.publishOnly(List.of(lost))).thenReturn(List.of(lost.getId()));

        sweeper.sweep();

        assertThat(lost.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(lost.getUpdatedAt()).isNotNull();
    }

    @Test
    void marksMessagesPendingAgainWhenThePublishFails() {
        NotificationMessage stuck = message(MessageStatus.PROCESSING);
        when(messages.lockStale(eq("PROCESSING"), any(), anyInt())).thenReturn(List.of(stuck));
        when(outbox.publishOnly(List.of(stuck))).thenReturn(List.of());

        sweeper.sweep();

        assertThat(stuck.getStatus()).isEqualTo(MessageStatus.PENDING);
    }
}
