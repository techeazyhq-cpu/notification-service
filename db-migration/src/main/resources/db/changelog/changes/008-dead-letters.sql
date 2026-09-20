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


--changeset notification:008-dead-letters
--comment Why a message failed for good and how often it was reprocessed, so failed messages can be found and reprocessed in bulk (see ADR-010).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'notification_message' AND column_name = 'failure_kind'
ALTER TABLE notification_message ADD COLUMN failure_kind VARCHAR(16);
ALTER TABLE notification_message ADD COLUMN reprocess_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_message ADD CONSTRAINT ck_message_failure_kind CHECK (failure_kind IN ('PERMANENT', 'EXHAUSTED', 'DEAD_LETTERED'));

UPDATE notification_message
SET failure_kind = CASE WHEN last_error LIKE 'Gave up after%' THEN 'EXHAUSTED' ELSE 'PERMANENT' END
WHERE status = 'FAILED';

CREATE INDEX ix_message_dead_letters ON notification_message (updated_at DESC) WHERE status = 'FAILED';
--rollback DROP INDEX ix_message_dead_letters;
--rollback ALTER TABLE notification_message DROP CONSTRAINT ck_message_failure_kind;
--rollback ALTER TABLE notification_message DROP COLUMN reprocess_count;
--rollback ALTER TABLE notification_message DROP COLUMN failure_kind;
