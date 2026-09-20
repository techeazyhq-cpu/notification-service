package com.techeazy.notification.billing.application.port;

import com.techeazy.notification.domain.Channel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read side of metering: what was actually sent. Only messages handed to a provider successfully are billable. */
public interface UsageReader {

    Map<Channel, Long> sentByChannel(UUID clientId, Instant fromInclusive, Instant toExclusive);
}
