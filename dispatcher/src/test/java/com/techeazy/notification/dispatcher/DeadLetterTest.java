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

package com.techeazy.notification.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class DeadLetterTest {

    private final NotificationMessageRepository messages = mock(NotificationMessageRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeadLetterRecorder recorder = new DeadLetterRecorder(messages, meters);
    private final DeadLetterConsumers consumers = new DeadLetterConsumers(mock(PulsarClient.class), new NotificationProperties(), recorder, new ObjectMapper());
    private final Consumer<byte[]> consumer = mock(Consumer.class);
    private final UUID id = UUID.randomUUID();

    private Message<byte[]> broker(String json) {
        Message<byte[]> message = mock(Message.class);
        when(message.getData()).thenReturn(json.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    @Test
    void aMessageStillInFlightBecomesAFailedDeadLetterAndIsCounted() {
        when(messages.markDeadLettered(eq(id), eq(MessageStatus.IN_FLIGHT), eq(DeadLetterRecorder.REASON), any())).thenReturn(1);

        assertThat(recorder.record(id, "SMS")).isTrue();

        assertThat(meters.get("notification.dead_letter").tag("channel", "SMS").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aMessageThatAlreadyFinishedIsLeftAlone() {
        when(messages.markDeadLettered(eq(id), any(), anyString(), any())).thenReturn(0);

        assertThat(recorder.record(id, "SMS")).isFalse();

        assertThat(meters.find("notification.dead_letter").counter()).isNull();
    }

    @Test
    void aDeadLetterFromTheBrokerIsRecordedAndAcknowledged() throws Exception {
        when(messages.markDeadLettered(eq(id), any(), anyString(), any())).thenReturn(1);
        Message<byte[]> message = broker("{\"v\":1,\"messageId\":\"" + id + "\",\"channel\":\"SMS\"}");

        consumers.handle(consumer, message, Channel.SMS);

        verify(consumer).acknowledge(message);
        verify(messages).markDeadLettered(eq(id), any(), anyString(), any());
    }

    @Test
    void aFailureToRecordMakesTheBrokerDeliverItAgain() throws Exception {
        when(messages.markDeadLettered(eq(id), any(), anyString(), any())).thenThrow(new IllegalStateException("db down"));
        Message<byte[]> message = broker("{\"v\":1,\"messageId\":\"" + id + "\",\"channel\":\"SMS\"}");

        consumers.handle(consumer, message, Channel.SMS);

        verify(consumer).negativeAcknowledge(message);
        verify(consumer, never()).acknowledge(message);
    }

    @Test
    void anUnreadableDeadLetterIsDroppedInsteadOfBeingRedeliveredForever() {
        Message<byte[]> message = broker("not json");

        consumers.handle(consumer, message, Channel.EMAIL);

        verify(consumer).acknowledgeAsync(message);
        verify(consumer, never()).negativeAcknowledge(message);
    }
}
