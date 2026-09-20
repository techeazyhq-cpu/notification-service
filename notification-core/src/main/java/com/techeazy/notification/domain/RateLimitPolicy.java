package com.techeazy.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Token-bucket policy: {@code ratePerSecond} refill, {@code burst} capacity. */
@Entity
@Table(name = "rate_limit_policy")
@Getter @Setter @NoArgsConstructor
public class RateLimitPolicy {
    @Id private UUID id;
    @Enumerated(EnumType.STRING) private RateLimitScope scope;
    private UUID clientId;
    @Enumerated(EnumType.STRING) private Channel channel;
    private BigDecimal ratePerSecond;
    private int burst;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
