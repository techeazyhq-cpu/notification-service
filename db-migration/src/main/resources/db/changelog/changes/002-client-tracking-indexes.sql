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


--changeset notification:002-client-tracking-indexes
--comment "My recent requests" and "my message counts over a window" both filter by client and time (formerly Flyway V2).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'ix_request_client_created'
CREATE INDEX ix_request_client_created ON notification_request (client_id, created_at DESC);
CREATE INDEX ix_message_client_created ON notification_message (client_id, created_at);
--rollback DROP INDEX ix_message_client_created;
--rollback DROP INDEX ix_request_client_created;
