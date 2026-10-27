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

--changeset notification:022-traffic-hourly
--comment Per-tenant, per-channel hourly traffic features that the anomaly detection of ADR-039 scores against each tenant's own history. Counts and calling codes or e-mail domains only, never an address, so rows outlive the erasure of personal data. A new table: nothing existing is locked.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = 'traffic_hourly'
CREATE TABLE traffic_hourly (
    client_id        UUID        NOT NULL,
    channel          VARCHAR(16) NOT NULL,
    hour             TIMESTAMPTZ NOT NULL,
    messages         INTEGER     NOT NULL,
    recipients       INTEGER     NOT NULL,
    new_recipients   INTEGER     NOT NULL,
    failed           INTEGER     NOT NULL,
    largest_request  INTEGER     NOT NULL,
    destinations     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    error_codes      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    computed_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, channel, hour),
    CONSTRAINT ck_traffic_hourly_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP', 'PUSH')),
    CONSTRAINT ck_traffic_hourly_whole_hour CHECK (hour = date_trunc('hour', hour AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')
);
CREATE INDEX ix_traffic_hourly_hour ON traffic_hourly (hour);
--rollback DROP TABLE traffic_hourly;
