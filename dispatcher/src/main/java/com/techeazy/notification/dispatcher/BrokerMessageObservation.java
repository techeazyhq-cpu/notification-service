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
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import org.apache.pulsar.client.api.Message;

import java.util.UUID;

/**
 * The observation around handling one broker message. It reads the trace context the publisher put in the message
 * properties, so the handling, its provider calls and its log lines join the trace of the request that accepted the
 * message (see ADR-023). Its timer, {@value #NAME}, measures handling per channel.
 */
final class BrokerMessageObservation {

    static final String NAME = "notification.consume";
    private static final String MESSAGE_ID = "notification.message_id";

    private BrokerMessageObservation() {
    }

    static Observation receiving(Message<byte[]> message, Channel channel, ObservationRegistry observations) {
        ReceiverContext<Message<byte[]>> context = new ReceiverContext<>(Message::getProperty);
        context.setCarrier(message);
        context.setRemoteServiceName("pulsar");
        return Observation.createNotStarted(NAME, () -> context, observations)
                .contextualName("notification consume")
                .lowCardinalityKeyValue("channel", channel.name());
    }

    /** The message id is only known once the payload has been read, inside the observation. */
    static void identify(Observation handling, UUID messageId) {
        handling.highCardinalityKeyValue(MESSAGE_ID, messageId.toString());
    }
}
