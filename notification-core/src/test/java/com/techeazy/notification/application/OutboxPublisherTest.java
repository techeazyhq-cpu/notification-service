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
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import com.techeazy.notification.port.BrokerUnavailableException;
import com.techeazy.notification.port.MessagePublisher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private final MessagePublisher publisher = mock(MessagePublisher.class);
    private final OutboxPublisher outbox = new OutboxPublisher(publisher, mock(NotificationMessageRepository.class),
            mock(QueuedMarker.class), new NotificationProperties());

    @Test
    void whileTheBrokerCircuitIsOpenAWholeBatchIsLeftForTheSweeperWithoutWaiting() {
        when(publisher.publish(any(), any(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new BrokerUnavailableException()));
        List<NotificationMessage> batch = IntStream.range(0, 200).mapToObj(index -> message()).toList();

        long started = System.nanoTime();
        List<UUID> confirmed = outbox.publishOnly(batch);

        assertThat(confirmed).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void confirmedPublishesAreReported() {
        when(publisher.publish(any(), any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        NotificationMessage message = message();

        assertThat(outbox.publishOnly(List.of(message))).containsExactly(message.getId());
    }

    private static NotificationMessage message() {
        NotificationMessage message = new NotificationMessage();
        message.setId(UUID.randomUUID());
        message.setClientId(UUID.randomUUID());
        message.setChannel(Channel.SMS);
        return message;
    }
}
