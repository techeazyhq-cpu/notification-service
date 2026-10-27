/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi.anomaly;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Records anomalies in {@code traffic_anomaly}. An anomaly already recorded for the same tenant, channel, hour and
 * feature is left exactly as it is: a later or concurrent run never duplicates it, and never overwrites an
 * administrator's decision on it.
 */
class AnomalyStore {

    private static final String INSERT = """
            INSERT INTO traffic_anomaly (id, client_id, channel, hour, feature, method, expected, actual, score,
                                         severity, detail, status, detected_at)
            VALUES (:id, :clientId, :channel, :hour, :feature, :method, :expected, :actual, :score, :severity, :detail,
                    'OPEN', :detectedAt)
            ON CONFLICT (client_id, channel, hour, feature) DO NOTHING""";

    private final JdbcClient jdbc;

    AnomalyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the anomalies not recorded yet.
     *
     * @param anomalies  what was found
     * @param detectedAt when it was found
     * @return the anomalies that were new
     */
    List<Anomaly> record(List<Anomaly> anomalies, Instant detectedAt) {
        Timestamp at = Timestamp.from(detectedAt);
        return anomalies.stream().filter(anomaly -> insert(anomaly, at)).toList();
    }

    private boolean insert(Anomaly anomaly, Timestamp detectedAt) {
        return jdbc.sql(INSERT)
                .param("id", UUID.randomUUID())
                .param("clientId", anomaly.clientId())
                .param("channel", anomaly.channel())
                .param("hour", Timestamp.from(anomaly.hour()))
                .param("feature", anomaly.feature().name())
                .param("method", anomaly.method().name())
                .param("expected", anomaly.expected())
                .param("actual", anomaly.actual())
                .param("score", anomaly.score())
                .param("severity", anomaly.severity().name())
                .param("detail", anomaly.detail())
                .param("detectedAt", detectedAt)
                .update() == 1;
    }
}
