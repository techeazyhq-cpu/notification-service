package com.techeazy.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** An API consumer (a calling application). Only the SHA-256 of its API key is stored. */
@Entity
@Table(name = "client")
@Getter @Setter @NoArgsConstructor
public class Client {
    @Id private UUID id;
    private String name;
    private String apiKeyHash;
    private String apiKeyPrefix;
    @Enumerated(EnumType.STRING) private ClientStatus status;
    private String allowedChannels;
    private Instant createdAt;
    private Instant updatedAt;

    public boolean isActive() {
        return status == ClientStatus.ACTIVE;
    }

    @Transient
    public Set<Channel> getAllowedChannelSet() {
        if (allowedChannels == null || allowedChannels.isBlank()) return EnumSet.noneOf(Channel.class);
        return Arrays.stream(allowedChannels.split(",")).map(String::trim).map(Channel::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Channel.class)));
    }

    public void setAllowedChannelSet(Set<Channel> channels) {
        this.allowedChannels = channels.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public boolean allows(Channel channel) {
        return getAllowedChannelSet().contains(channel);
    }
}
