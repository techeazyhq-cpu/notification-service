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
