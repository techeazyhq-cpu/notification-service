package com.techeazy.notification.dispatcher.provider;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.ProviderConfig;
import com.techeazy.notification.domain.ProviderType;

import java.util.UUID;

/** SPI for delivering one rendered message through one kind of provider. */
public interface ChannelProvider {

    record Outbound(UUID messageId, Channel channel, String recipient, String subject, String body) {}

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
