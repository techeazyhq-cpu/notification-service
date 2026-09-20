package com.techeazy.notification.port;

import com.techeazy.notification.domain.Channel;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Outbound port to the message broker. Only the message id travels; no recipient data. */
public interface MessagePublisher {
    CompletableFuture<Void> publish(Channel channel, UUID messageId, UUID clientId);
}
