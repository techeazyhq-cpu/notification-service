package com.techeazy.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** How a channel is delivered. The enabled config with the lowest priority number wins. */
@Entity
@Table(name = "provider_config")
@Getter @Setter @NoArgsConstructor
public class ProviderConfig {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) private Channel channel;
    private String name;
    @Enumerated(EnumType.STRING) private ProviderType type;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String, String> settings = new HashMap<>();
    private boolean enabled;
    private int priority;
    private Instant createdAt;
    private Instant updatedAt;
}
