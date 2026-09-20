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


--changeset notification:007-personal-data-retention
--comment Marks messages and requests whose personal data was erased, with partial indexes so the retention job only reads rows that are due (see ADR-009).
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'notification_message' AND column_name = 'erased_at'
ALTER TABLE notification_message ADD COLUMN erased_at TIMESTAMPTZ;
ALTER TABLE notification_request ADD COLUMN erased_at TIMESTAMPTZ;

CREATE INDEX ix_message_erase_due ON notification_message (updated_at) WHERE erased_at IS NULL AND status IN ('SENT', 'FAILED');
CREATE INDEX ix_request_erase_due ON notification_request (created_at) WHERE erased_at IS NULL;
CREATE INDEX ix_request_idempotency_due ON notification_request (created_at) WHERE idempotency_key IS NOT NULL;
--rollback DROP INDEX ix_request_idempotency_due;
--rollback DROP INDEX ix_request_erase_due;
--rollback DROP INDEX ix_message_erase_due;
--rollback ALTER TABLE notification_request DROP COLUMN erased_at;
--rollback ALTER TABLE notification_message DROP COLUMN erased_at;
