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
    @Convert(converter = EncryptedVariablesConverter.class) @JdbcTypeCode(SqlTypes.JSON) private Map<String, String> variables = new HashMap<>();
    @Enumerated(EnumType.STRING) private MessageStatus status;
    private int attempts;
    private String lastError;
    @Enumerated(EnumType.STRING) private FailureKind failureKind;
    private int reprocessCount;
    private String providerMessageId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant sentAt;
}
