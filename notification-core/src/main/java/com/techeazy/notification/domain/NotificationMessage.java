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

/** One recipient's delivery. Pulsar carries only its id; content and recipient live here. */
@Entity
@Table(name = "notification_message")
@Getter @Setter @NoArgsConstructor
public class NotificationMessage {
    @Id private UUID id;
    private UUID requestId;
    private UUID clientId;
    @Enumerated(EnumType.STRING) private Channel channel;
    private String recipient;
    @JdbcTypeCode(SqlTypes.JSON) private Map<String, String> variables = new HashMap<>();
    @Enumerated(EnumType.STRING) private MessageStatus status;
    private int attempts;
    private String lastError;
    private String providerMessageId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant sentAt;
}
