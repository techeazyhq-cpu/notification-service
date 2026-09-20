package com.techeazy.notification.clientapi;

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;

import java.util.Set;
import java.util.UUID;

/** What the API layer knows about the caller. Deliberately not the persistent {@link Client} entity. */
public record AuthenticatedClient(UUID id, String name, Set<Channel> allowedChannels) {

    public static AuthenticatedClient from(Client client) {
        return new AuthenticatedClient(client.getId(), client.getName(), Set.copyOf(client.getAllowedChannelSet()));
    }

    public boolean allows(Channel channel) {
        return allowedChannels.contains(channel);
    }
}
