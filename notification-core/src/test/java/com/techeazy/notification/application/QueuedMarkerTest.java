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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.port.MessagePublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueuedMarkerTest {

    private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    private final QueuedMarker marker = new QueuedMarker(messages, 3_600_000);

    @Test
    void flushMarksEverythingThatWasQueuedForMarkingInBatches() {
        List<UUID> ids = IntStream.range(0, 2500).mapToObj(i -> UUID.randomUUID()).toList();
        marker.markLater(ids);

        marker.flush();

        var captured = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(messages, times(3)).markQueued(captured.capture(), any(Instant.class));
        assertThat(captured.getAllValues()).extracting(Collection::size).containsExactly(1000, 1000, 500);
        assertThat(marker.backlog()).isZero();
    }

    @Test
    void flushWithNothingPendingDoesNotTouchTheDatabase() {
        marker.flush();

        verify(messages, never()).markQueued(anyCollection(), any());
    }

    @Test
    void aFailingUpdateIsSwallowedBecauseTheSweeperRecoversTheMessages() {
        when(messages.markQueued(anyCollection(), any())).thenThrow(new IllegalStateException("db down"));
        marker.markLater(List.of(UUID.randomUUID()));

        marker.flush();

        assertThat(marker.backlog()).isZero();
    }

    @Test
    void publishAndMarkQueuedLaterMarksOnlyTheMessagesTheBrokerConfirmed() {
        MessagePublisher publisher = mock(MessagePublisher.class);
        OutboxPublisher outbox = new OutboxPublisher(publisher, messages, marker);
        NotificationMessage ok = message();
        NotificationMessage failed = message();
        when(publisher.publish(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(1).equals(ok.getId())
                ? CompletableFuture.<Void>completedFuture(null)
                : CompletableFuture.<Void>failedFuture(new IllegalStateException("broker down")));

        List<UUID> confirmed = outbox.publishAndMarkQueuedLater(List.of(ok, failed));

        assertThat(confirmed).containsExactly(ok.getId());
        assertThat(marker.backlog()).isEqualTo(1);
        verify(messages, never()).markQueued(anyCollection(), any());
    }

    private static NotificationMessage message() {
        NotificationMessage m = new NotificationMessage();
        m.setId(UUID.randomUUID());
        m.setClientId(UUID.randomUUID());
        m.setChannel(Channel.SMS);
        return m;
    }
}
