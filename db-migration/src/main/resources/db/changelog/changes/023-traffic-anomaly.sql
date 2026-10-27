--liquibase formatted sql

-- Copyright 2026 Vasantha Kumar Kesavan
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>

--changeset notification:023-traffic-anomaly
--comment Traffic anomalies scored from traffic_hourly, with what was expected, what happened and how an administrator resolved it (ADR-039). One row per tenant, channel, hour and feature, so concurrent scorers never duplicate one. A new table: nothing existing is locked.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'traffic_anomaly'
CREATE TABLE traffic_anomaly (
    id               UUID             PRIMARY KEY,
    client_id        UUID             NOT NULL,
    channel          VARCHAR(16)      NOT NULL,
    hour             TIMESTAMPTZ      NOT NULL,
    feature          VARCHAR(32)      NOT NULL,
    method           VARCHAR(16)      NOT NULL,
    expected         DOUBLE PRECISION NOT NULL,
    actual           DOUBLE PRECISION NOT NULL,
    score            DOUBLE PRECISION NOT NULL,
    severity         VARCHAR(8)       NOT NULL,
    detail           VARCHAR(200),
    status           VARCHAR(16)      NOT NULL DEFAULT 'OPEN',
    detected_at      TIMESTAMPTZ      NOT NULL,
    resolved_by      VARCHAR(100),
    resolved_at      TIMESTAMPTZ,
    resolution_note  VARCHAR(500),
    CONSTRAINT ux_traffic_anomaly_hour_feature UNIQUE (client_id, channel, hour, feature),
    CONSTRAINT ck_traffic_anomaly_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP', 'PUSH')),
    CONSTRAINT ck_traffic_anomaly_feature CHECK (feature IN ('MESSAGES', 'NEW_RECIPIENTS', 'LARGEST_REQUEST', 'FAILURE_RATE', 'NEW_DESTINATION')),
    CONSTRAINT ck_traffic_anomaly_method CHECK (method IN ('BASELINE', 'COLD_START', 'NEW_DESTINATION')),
    CONSTRAINT ck_traffic_anomaly_severity CHECK (severity IN ('WARNING', 'HIGH')),
    CONSTRAINT ck_traffic_anomaly_status CHECK (status IN ('OPEN', 'CONFIRMED', 'FALSE_ALARM')),
    CONSTRAINT ck_traffic_anomaly_resolution CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);
CREATE INDEX ix_traffic_anomaly_open ON traffic_anomaly (detected_at DESC) WHERE status = 'OPEN';
CREATE INDEX ix_traffic_anomaly_client ON traffic_anomaly (client_id, hour DESC);
--rollback DROP TABLE traffic_anomaly;
