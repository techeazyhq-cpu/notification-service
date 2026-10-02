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
package com.techeazy.notification.dispatcher;

import com.techeazy.notification.domain.MessageStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** One grouped query over the in-flight rows, served by the (status, updated_at) index. */
@Component
class JdbcBacklogReader implements BacklogReader {

    private final JdbcClient jdbc;

    JdbcBacklogReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BacklogSnapshot> read() {
        return jdbc.sql("""
                SELECT status, count(*) AS messages, min(created_at) AS oldest_created_at
                FROM notification_message
                WHERE status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRYING')
                GROUP BY status
                """)
                .query((row, rowNumber) -> new BacklogSnapshot(MessageStatus.valueOf(row.getString("status")),
                        row.getLong("messages"), row.getTimestamp("oldest_created_at").toInstant()))
                .list();
    }
}
