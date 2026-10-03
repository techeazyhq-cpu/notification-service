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



--changeset notification:015-sent-usage-by-category runInTransaction:false
--comment Usage counts one-time passwords apart (ADR-034), so the index usage is read from carries the category and the count stays an index-only scan. Built concurrently and checked for validity (ADR-003).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = current_schema() AND c.relname = 'ix_message_sent_usage_category' AND i.indisvalid
DROP INDEX CONCURRENTLY IF EXISTS ix_message_sent_usage_category;
CREATE INDEX CONCURRENTLY ix_message_sent_usage_category ON notification_message (client_id, channel, sent_at)
    INCLUDE (category) WHERE status = 'SENT';
DROP INDEX CONCURRENTLY IF EXISTS ix_message_sent_usage;
--rollback CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_message_sent_usage ON notification_message (client_id, channel, sent_at) WHERE status = 'SENT';
--rollback DROP INDEX CONCURRENTLY IF EXISTS ix_message_sent_usage_category;
