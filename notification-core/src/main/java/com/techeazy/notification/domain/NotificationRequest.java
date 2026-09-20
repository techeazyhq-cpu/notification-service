package com.techeazy.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A client submission: one message (SINGLE) or many recipients sharing content (BULK). */
@Entity
@Table(name = "notification_request")
@Getter @Setter @NoArgsConstructor
public class NotificationRequest {
    @Id private UUID id;
    private UUID clientId;
    @Enumerated(EnumType.STRING) private RequestKind kind;
    @Enumerated(EnumType.STRING) private Channel channel;
    private UUID templateId;
    private String subject;
    private String body;
    private int total;
    private String idempotencyKey;
    private String clientReference;
    private Instant createdAt;
}
