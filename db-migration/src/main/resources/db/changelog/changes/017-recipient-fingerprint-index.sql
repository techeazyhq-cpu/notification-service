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



--changeset notification:017-recipient-fingerprint-index runInTransaction:false
--comment Finds a tenant's messages to one recipient by fingerprint, for the recipient activity report (ADR-035). Built concurrently and checked for validity (ADR-003).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND c.relname = 'ix_message_client_fingerprint' AND i.indisvalid
DROP INDEX CONCURRENTLY IF EXISTS ix_message_client_fingerprint;
CREATE INDEX CONCURRENTLY ix_message_client_fingerprint ON notification_message (client_id, recipient_fingerprint, created_at)
    WHERE recipient_fingerprint IS NOT NULL;
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_message_client_fingerprint;
