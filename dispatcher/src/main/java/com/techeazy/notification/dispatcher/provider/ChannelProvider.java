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

package com.techeazy.notification.dispatcher.provider;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;

import java.util.UUID;

/** SPI for delivering one rendered message through one kind of provider. */
public interface ChannelProvider {

    /** {@code fromAddress} and {@code fromName} are the client's own verified sender; both null means the provider's default. */
    record Outbound(UUID messageId, Channel channel, String recipient, String subject, String body, String fromAddress, String fromName) {

        public Outbound(UUID messageId, Channel channel, String recipient, String subject, String body) {
            this(messageId, channel, recipient, subject, body, null, null);
        }
    }

    record SendResult(String providerMessageId) {}

    /** Retrying the same provider may help (timeout, 5xx, throttling). */
    class TransientSendException extends RuntimeException {
        public TransientSendException(String message, Throwable cause) { super(message, cause); }
        public TransientSendException(String message) { super(message); }
    }

    /** Retrying cannot help (bad recipient, rejected content). */
    class PermanentSendException extends RuntimeException {
        public PermanentSendException(String message, Throwable cause) { super(message, cause); }
        public PermanentSendException(String message) { super(message); }
    }

    ProviderType type();

    SendResult send(ProviderConfig config, Outbound message);
}
