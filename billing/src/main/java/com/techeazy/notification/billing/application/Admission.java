package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.domain.Channel;

import java.util.UUID;

/** What a client asks to send: how many messages on which channel, and the request or message they belong to. */
public record Admission(UUID clientId, Channel channel, long messages, HoldScope scope, UUID referenceId) {}
