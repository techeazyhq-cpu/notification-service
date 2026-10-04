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

import com.techeazy.notification.error.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry of a message's event log (ADR-035). It never holds personal data: no address, no content and no
 * provider wording, which can quote the recipient. The error code says why; {@code detail} is fixed text.
 */
public record MessageEvent(UUID messageId, UUID clientId, MessageEventType type, Instant occurredAt, Integer attempt,
                           ErrorCode errorCode, String providerMessageId, String detail) {

    public static MessageEvent of(NotificationMessage message, MessageEventType type, Instant occurredAt) {
        return new MessageEvent(message.getId(), message.getClientId(), type, occurredAt, null, null, null, null);
    }

    public MessageEvent withAttempt(int number) {
        return new MessageEvent(messageId, clientId, type, occurredAt, number, errorCode, providerMessageId, detail);
    }

    public MessageEvent withError(ErrorCode code) {
        return new MessageEvent(messageId, clientId, type, occurredAt, attempt, code, providerMessageId, detail);
    }

    public MessageEvent withProviderMessageId(String id) {
        return new MessageEvent(messageId, clientId, type, occurredAt, attempt, errorCode, id, detail);
    }

    public MessageEvent withDetail(String text) {
        return new MessageEvent(messageId, clientId, type, occurredAt, attempt, errorCode, providerMessageId, text);
    }
}
