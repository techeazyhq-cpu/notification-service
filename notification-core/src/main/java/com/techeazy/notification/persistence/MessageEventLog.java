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
package com.techeazy.notification.persistence;

import com.techeazy.notification.domain.MessageEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * Appends to the message event log (ADR-035). Callers write an event in the transaction that makes the change it
 * records, so the log and the message never disagree. The table refuses updates; rows go only when retention deletes
 * their message.
 */
@Repository
public class MessageEventLog {

    private final JdbcClient jdbc;

    public MessageEventLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(MessageEvent event) {
        jdbc.sql("""
                INSERT INTO message_event (message_id, client_id, event, occurred_at, attempt, error_code,
                                           provider_message_id, detail)
                VALUES (:message, :client, :event, :at, :attempt, :errorCode, :providerMessageId, :detail)""")
                .param("message", event.messageId()).param("client", event.clientId())
                .param("event", event.type().name()).param("at", Timestamp.from(event.occurredAt()))
                .param("attempt", event.attempt())
                .param("errorCode", event.errorCode() == null ? null : event.errorCode().name())
                .param("providerMessageId", event.providerMessageId()).param("detail", event.detail())
                .update();
    }
}
