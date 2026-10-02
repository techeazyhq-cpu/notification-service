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

package com.techeazy.notification.infra;

import com.techeazy.notification.domain.MessageCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.config.NotificationProperties;
import com.techeazy.notification.domain.Channel;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.observation.transport.SenderContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PulsarMessagePublisherTest {

    private final PulsarClient client = mock(PulsarClient.class);
    @SuppressWarnings("unchecked")
    private final ProducerBuilder<byte[]> builder = mock(ProducerBuilder.class, Mockito.RETURNS_SELF);
    @SuppressWarnings("unchecked")
    private final Producer<byte[]> producer = mock(Producer.class);
    @SuppressWarnings("unchecked")
    private final TypedMessageBuilder<byte[]> message = mock(TypedMessageBuilder.class, Mockito.RETURNS_SELF);
    private final TestObservationRegistry observations = TestObservationRegistry.create();
    private final PulsarMessagePublisher publisher =
            new PulsarMessagePublisher(client, new NotificationProperties(), new ObjectMapper(), observations);

    /** Stands in for the tracing handler: writes a W3C trace header into whatever carrier a send provides. */
    private static final class TraceHeaderWriter implements ObservationHandler<SenderContext<Object>> {
        @Override
        public void onStart(SenderContext<Object> context) {
            context.getSetter().set(context.getCarrier(), "traceparent", TRACE_PARENT);
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof SenderContext;
        }
    }

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @SuppressWarnings("unchecked")
    private void brokerAvailable() {
        when(client.newProducer()).thenReturn(builder);
        when(builder.createAsync()).thenReturn(CompletableFuture.completedFuture(producer));
        when(producer.newMessage()).thenReturn(message);
        when(message.sendAsync()).thenReturn(CompletableFuture.completedFuture(mock(MessageId.class)));
    }

    @Test
    void publishesTheMessageIdAndReusesTheProducer() {
        brokerAvailable();

        publisher.publish(Channel.SMS, MessageCategory.TRANSACTIONAL, UUID.randomUUID(), UUID.randomUUID()).join();
        publisher.publish(Channel.SMS, MessageCategory.TRANSACTIONAL, UUID.randomUUID(), UUID.randomUUID()).join();

        verify(builder, times(1)).createAsync();
        verify(message, times(2)).sendAsync();
    }

    @Test
    void oneTimePasswordsGoToTheChannelsPriorityTopicAndEverythingElseToItsStandardTopic() {
        brokerAvailable();

        publisher.publish(Channel.SMS, MessageCategory.OTP, UUID.randomUUID(), UUID.randomUUID()).join();
        publisher.publish(Channel.SMS, MessageCategory.PROMOTIONAL, UUID.randomUUID(), UUID.randomUUID()).join();

        verify(builder).topic("persistent://public/default/notification-sms-priority");
        verify(builder).topic("persistent://public/default/notification-sms");
        verify(builder, times(2)).createAsync();
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgetsAProducerThatCouldNotBeCreatedSoTheNextPublishTriesAgain() {
        when(client.newProducer()).thenReturn(builder);
        when(builder.createAsync()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenReturn(CompletableFuture.completedFuture(producer));
        when(producer.newMessage()).thenReturn(message);
        when(message.sendAsync()).thenReturn(CompletableFuture.completedFuture(mock(MessageId.class)));
        UUID id = UUID.randomUUID();
        UUID client = UUID.randomUUID();

        CompletableFuture<Void> first = publisher.publish(Channel.SMS, MessageCategory.TRANSACTIONAL, id, client);

        assertThatThrownBy(first::get).isInstanceOf(ExecutionException.class).hasRootCauseMessage("broker down");
        assertThat(publisher.publish(Channel.SMS, MessageCategory.TRANSACTIONAL, id, client).join()).isNull();
        verify(builder, times(2)).createAsync();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTraceOfTheRequestThatPublishesTravelsInTheMessageProperties() {
        brokerAvailable();
        observations.observationConfig().observationHandler(new TraceHeaderWriter());
        Observation request = Observation.start("http.server.requests", observations);

        try (Observation.Scope scope = request.openScope()) {
            publisher.publish(Channel.SMS, MessageCategory.TRANSACTIONAL, UUID.randomUUID(), UUID.randomUUID()).join();
        } finally {
            request.stop();
        }

        ArgumentCaptor<Map<String, String>> properties = ArgumentCaptor.forClass(Map.class);
        verify(message).properties(properties.capture());
        assertThat(properties.getValue()).containsEntry("traceparent", TRACE_PARENT);
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(PulsarMessagePublisher.PUBLISH_OBSERVATION).that()
                .hasBeenStarted().hasBeenStopped()
                .hasParentObservationEqualTo(request)
                .hasLowCardinalityKeyValue("channel", "SMS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedPublishIsRecordedOnItsObservation() {
        when(client.newProducer()).thenReturn(builder);
        when(builder.createAsync())
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        CompletableFuture<Void> publish = publisher.publish(Channel.SMS, MessageCategory.TRANSACTIONAL,
                UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(publish::join).hasRootCauseMessage("broker down");
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(PulsarMessagePublisher.PUBLISH_OBSERVATION).that()
                .hasBeenStopped().assertThatError().hasRootCauseMessage("broker down");
    }

    @Test
    void closesEveryProducerOnShutdown() {
        brokerAvailable();
        publisher.publish(Channel.EMAIL, MessageCategory.TRANSACTIONAL, UUID.randomUUID(), UUID.randomUUID()).join();

        publisher.close();

        verify(producer).closeAsync();
    }
}
