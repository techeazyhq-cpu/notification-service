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
