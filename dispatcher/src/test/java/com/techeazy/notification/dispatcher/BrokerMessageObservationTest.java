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

import com.techeazy.notification.domain.Channel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.observation.transport.ReceiverContext;
import org.apache.pulsar.client.api.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrokerMessageObservationTest {

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final TestObservationRegistry observations = TestObservationRegistry.create();

    /** Stands in for the tracing handler: reads the W3C trace header from the received carrier. */
    private static final class TraceHeaderReader implements ObservationHandler<ReceiverContext<Object>> {
        private final List<String> read = new ArrayList<>();

        @Override
        public void onStart(ReceiverContext<Object> context) {
            read.add(context.getGetter().get(context.getCarrier(), "traceparent"));
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ReceiverContext;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlingAMessageContinuesTheTraceItWasPublishedIn() {
        TraceHeaderReader reader = new TraceHeaderReader();
        observations.observationConfig().observationHandler(reader);
        Message<byte[]> message = mock(Message.class);
        when(message.getProperty("traceparent")).thenReturn(TRACE_PARENT);
        UUID messageId = UUID.randomUUID();

        Observation handling = BrokerMessageObservation.receiving(message, Channel.SMS, observations);
        handling.observe(() -> BrokerMessageObservation.identify(handling, messageId));

        assertThat(reader.read).containsExactly(TRACE_PARENT);
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo(BrokerMessageObservation.NAME).that()
                .hasBeenStarted().hasBeenStopped()
                .hasLowCardinalityKeyValue("channel", "SMS")
                .hasHighCardinalityKeyValue("notification.message_id", messageId.toString());
    }
}
