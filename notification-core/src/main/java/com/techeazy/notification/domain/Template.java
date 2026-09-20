package com.techeazy.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Message template using {{variable}} placeholders. {{recipient}} is always available. */
@Entity
@Table(name = "template")
@Getter @Setter @NoArgsConstructor
public class Template {
    @Id private UUID id;
    private String name;
    @Enumerated(EnumType.STRING) private Channel channel;
    private String subject;
    private String body;
    private Instant createdAt;
    private Instant updatedAt;
}
