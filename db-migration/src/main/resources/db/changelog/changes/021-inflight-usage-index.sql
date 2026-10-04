--liquibase formatted sql

-- Copyright 2026 Vasantha Kumar
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
-- @author Vasantha Kumar <vasantha.kumar@hotmail.com>

--changeset notification:021-inflight-usage-index runInTransaction:false
--comment Counts a tenant's messages still in flight per channel and category, read at every postpaid accept with a spend cap. Partial on the in-flight statuses, which are few at any moment, so it stays small. Built concurrently and checked for validity (ADR-003).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND c.relname = 'ix_message_inflight_usage' AND i.indisvalid
DROP INDEX CONCURRENTLY IF EXISTS ix_message_inflight_usage;
CREATE INDEX CONCURRENTLY ix_message_inflight_usage ON notification_message (client_id, channel, category)
    WHERE status IN ('PENDING', 'QUEUED', 'PROCESSING', 'RETRYING');
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_message_inflight_usage;
